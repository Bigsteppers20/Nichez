package app.nichez.interaction.service;

import app.nichez.activity.service.Notifier;
import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.common.TimeAgo;
import app.nichez.config.SocialProperties;
import app.nichez.interaction.dto.InteractionDtos.CommentReactionResponse;
import app.nichez.interaction.dto.InteractionDtos.CommentResponse;
import app.nichez.interaction.dto.InteractionDtos.CreateCommentRequest;
import app.nichez.interaction.repository.CommentRepository;
import app.nichez.interaction.repository.CommentRepository.Basic;
import app.nichez.interaction.repository.CommentRepository.Row;
import app.nichez.post.entity.Post;
import app.nichez.post.service.PostAccess;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments under a post, one level deep. Who is asking always comes from the token. Reading,
 * writing and reacting all need a post the caller can see; any other case is the same 404, so a
 * response never reveals a hidden post or comment.
 */
@Service
public class CommentService {

    public static final String COMMENT_NOT_FOUND = "COMMENT_NOT_FOUND";
    public static final String COMMENTS_DISABLED = "COMMENTS_DISABLED";
    public static final String INVALID_SORT = "INVALID_SORT";

    private static final int MAX_MENTIONS = 5;

    private final CommentRepository comments;
    private final PostAccess access;
    private final UserBriefs briefs;
    private final UserRepository users;
    private final BlockPolicy blocks;
    private final Notifier notifier;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    CommentService(CommentRepository comments, PostAccess access, UserBriefs briefs, UserRepository users,
            BlockPolicy blocks, Notifier notifier, SlidingWindowRateLimiter limiter, SocialProperties social,
            Clock clock) {
        this.comments = comments;
        this.access = access;
        this.briefs = briefs;
        this.users = users;
        this.blocks = blocks;
        this.notifier = notifier;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    /**
     * Top-level comments with their replies after them. {@code sort} is {@code top} (most reactions,
     * the default) or {@code new}. A page counts top-level comments only; every reply to them comes along.
     */
    @Transactional(readOnly = true)
    public CursorPage<CommentResponse> list(UUID postId, String sort, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        access.requireInteractable(me, postId);
        int limit = Paging.limit(requestedLimit);
        List<Row> fetched;
        java.util.function.Function<Row, String> cursorOf;
        if (sort == null || sort.isBlank() || "top".equals(sort)) {
            List<String> parts = Cursor.decode(cursor, 3);
            fetched = comments.best(me, postId, parts == null ? null : (int) Cursor.number(parts.get(0)),
                    parts == null ? null : Cursor.instant(parts.get(1)),
                    parts == null ? null : Cursor.uuid(parts.get(2)), limit + 1);
            cursorOf = r -> Cursor.encode(r.score(), r.createdAt(), r.id());
        } else if ("new".equals(sort)) {
            Cursor.TimeId after = Cursor.TimeId.decode(cursor);
            fetched = comments.newest(me, postId, after == null ? null : after.time(),
                    after == null ? null : after.id(), limit + 1);
            cursorOf = r -> new Cursor.TimeId(r.createdAt(), r.id()).encode();
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_SORT, "sort must be top or new.");
        }
        List<Row> parents = Paging.head(fetched, limit);
        List<Row> replies = comments.repliesOf(me, parents.stream().map(Row::id).toList());
        Map<UUID, List<Row>> repliesByParent = replies.stream().collect(
                Collectors.groupingBy(Row::parentId, LinkedHashMap::new, Collectors.toList()));
        List<Row> ordered = new ArrayList<>();
        for (Row parent : parents) {
            ordered.add(parent);
            ordered.addAll(repliesByParent.getOrDefault(parent.id(), List.of()));
        }
        return new CursorPage<>(toResponses(me, ordered), Paging.nextCursor(fetched, limit, cursorOf));
    }

    /**
     * Adds a comment, or a reply when {@code parentId} is set. A reply to a reply goes under the
     * same top-level comment. The post author, the person replied to and anyone mentioned are told.
     */
    @Transactional
    public CommentResponse create(UUID postId, CreateCommentRequest request) {
        UUID me = CurrentUser.id();
        limiter.check("comment:" + me, social.commentsPerHour(), Duration.ofHours(1));
        Post post = access.requireInteractable(me, postId);
        if (!post.isAllowComments()) {
            throw new ApiException(HttpStatus.FORBIDDEN, COMMENTS_DISABLED, "Comments are turned off for this post.");
        }
        UUID parentId = null;
        UUID parentAuthor = null;
        if (request.parentId() != null) {
            Basic parent = comments.find(request.parentId())
                    .filter(c -> !c.deleted() && c.postId().equals(postId))
                    .filter(c -> !blocks.isBlockedEitherWay(me, c.authorId()))
                    .orElseThrow(CommentService::notFound);
            // One level deep: a reply to a reply lands under the comment it answers.
            parentId = parent.parentId() != null ? parent.parentId() : parent.id();
            parentAuthor = parent.authorId();
        }
        UUID id = UUID.randomUUID();
        String body = request.body().strip();
        comments.insert(id, postId, me, parentId, body, clock.instant());

        Set<UUID> told = new HashSet<>();
        told.add(me);
        if (parentAuthor != null && told.add(parentAuthor)) {
            notifier.repliedTo(me, parentAuthor, post, id);
        }
        if (told.add(post.getAuthorId())) {
            notifier.commented(me, post, id);
        }
        for (UUID mentioned : users.idsByHandles(MentionParser.handles(body, MAX_MENTIONS)).values()) {
            if (told.add(mentioned)) {
                notifier.mentioned(me, mentioned, post, id);
            }
        }
        return toResponses(me, List.of(comments.one(me, id).orElseThrow())).get(0);
    }

    /** Deletes the caller's own comment and the replies under it. Anyone else's is the same 404. */
    @Transactional
    public void delete(UUID commentId) {
        UUID me = CurrentUser.id();
        Basic comment = comments.find(commentId)
                .filter(c -> !c.deleted() && c.authorId().equals(me))
                .orElseThrow(CommentService::notFound);
        comments.softDelete(comment.id(), clock.instant());
    }

    /** Reacts to a comment. {@code kind} defaults to {@code love}. */
    @Transactional
    public CommentReactionResponse react(UUID commentId, String kind) {
        UUID me = CurrentUser.id();
        limiter.check("react:" + me, social.reactionsPerMinute(), Duration.ofMinutes(1));
        requireVisible(me, commentId);
        String chosen = kind == null ? "love" : kind;
        comments.react(commentId, me, chosen);
        return new CommentReactionResponse(chosen, scoreOrNull(comments.score(commentId)));
    }

    @Transactional
    public CommentReactionResponse clearReaction(UUID commentId) {
        UUID me = CurrentUser.id();
        limiter.check("react:" + me, social.reactionsPerMinute(), Duration.ofMinutes(1));
        requireVisible(me, commentId);
        comments.clearReaction(commentId, me);
        return new CommentReactionResponse(null, scoreOrNull(comments.score(commentId)));
    }

    private void requireVisible(UUID me, UUID commentId) {
        Basic comment = comments.find(commentId).filter(c -> !c.deleted()).orElseThrow(CommentService::notFound);
        access.requireInteractable(me, comment.postId());
        if (blocks.isBlockedEitherWay(me, comment.authorId())) {
            throw notFound();
        }
    }

    private List<CommentResponse> toResponses(UUID me, List<Row> rows) {
        Map<UUID, UserBrief> authors = briefs.load(rows.stream().map(Row::authorId).toList());
        Instant now = clock.instant();
        return rows.stream().map(row -> {
            UserBrief author = briefs.loadOrPlaceholder(row.authorId(), authors);
            String name = author.name() != null ? author.name() : author.handle();
            return new CommentResponse(row.id(), row.authorId(), name, author.handle(), author.avatarUrl(), row.body(),
                    row.createdAt(), TimeAgo.between(row.createdAt(), now), scoreOrNull(row.score()),
                    row.parentId() != null, row.parentId(), row.authorId().equals(me), row.myReaction());
        }).toList();
    }

    private static Integer scoreOrNull(int score) {
        return score > 0 ? score : null;
    }

    private static NotFoundException notFound() {
        return new NotFoundException(COMMENT_NOT_FOUND, "Comment not found.");
    }
}
