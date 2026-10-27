package app.nichez.post.service;

import app.nichez.common.ApiException;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.common.TimeAgo;
import app.nichez.config.SocialProperties;
import app.nichez.graph.service.FollowService;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaKind;
import app.nichez.media.service.MediaAttachments;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.post.dto.PostExtrasDtos.StoryGroup;
import app.nichez.post.dto.PostExtrasDtos.StoryItem;
import app.nichez.post.dto.PostExtrasDtos.StoryRequest;
import app.nichez.post.repository.StoryRepository;
import app.nichez.post.repository.StoryRepository.Row;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 24-hour stories. A story is visible to everyone it is not hidden from and who has no block with
 * its author; the home row shows the caller's own and those of the people they follow. Nothing has to
 * run for a story to expire: readers only look at stories whose expiry is in the future.
 */
@Service
public class StoryService {

    public static final String INVALID_STORY = "INVALID_STORY";
    public static final String STORY_NOT_FOUND = "STORY_NOT_FOUND";

    private final StoryRepository stories;
    private final MediaAttachments attachments;
    private final MediaUrlProvider urls;
    private final UserBriefs briefs;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final BlockPolicy blocks;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    StoryService(StoryRepository stories, MediaAttachments attachments, MediaUrlProvider urls, UserBriefs briefs,
            UserRepository users, ProfileRepository profiles, BlockPolicy blocks, SlidingWindowRateLimiter limiter,
            SocialProperties social, Clock clock) {
        this.stories = stories;
        this.attachments = attachments;
        this.urls = urls;
        this.briefs = briefs;
        this.users = users;
        this.profiles = profiles;
        this.blocks = blocks;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    /** A media story needs {@code mediaId}; a text story needs {@code body} and {@code color}; not both. */
    @Transactional
    public StoryItem create(StoryRequest request) {
        UUID me = CurrentUser.id();
        limiter.check("story:" + me, social.storiesPerDay(), Duration.ofDays(1));
        boolean hasMedia = request.mediaId() != null;
        boolean hasText = request.body() != null || request.color() != null;
        if (hasMedia == hasText || (hasText && (request.body() == null || request.color() == null))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_STORY,
                    "Send either a mediaId, or a body and a color.");
        }
        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        UUID mediaId = null;
        if (hasMedia) {
            Media media = attachments.attach(request.mediaId(), me, Set.of(MediaKind.IMAGE, MediaKind.VIDEO));
            mediaId = media.getId();
        }
        UUID id = stories.insert(me, mediaId, hasText ? request.body().strip() : null, request.color(),
                request.allowReplies() == null || request.allowReplies(),
                request.allowSharing() == null || request.allowSharing(), now, now.plus(social.storyTtl()));
        if (request.hiddenFromUserIds() != null && !request.hiddenFromUserIds().isEmpty()) {
            Set<UUID> hidden = new java.util.LinkedHashSet<>(request.hiddenFromUserIds());
            hidden.remove(me);
            stories.hideFrom(id, users.activeAmong(hidden));
        }
        return toItem(stories.visible(me, id, now).orElseThrow(), now);
    }

    /** The home row: the caller first, then people with stories they have not seen, then the rest. */
    @Transactional(readOnly = true)
    public CursorPage<StoryGroup> feed() {
        UUID me = CurrentUser.id();
        Instant now = clock.instant();
        List<StoryGroup> groups = group(me, stories.feed(me, now), now);
        groups.sort(Comparator
                .comparing((StoryGroup g) -> !g.isYou())
                .thenComparing(StoryGroup::seen)
                .thenComparing((StoryGroup g) -> g.items().get(g.items().size() - 1).createdAt(),
                        Comparator.reverseOrder()));
        return new CursorPage<>(groups, null);
    }

    /** One person's stories, as the caller may see them. A person with none gets an empty group. */
    @Transactional(readOnly = true)
    public StoryGroup of(UUID authorId) {
        UUID me = CurrentUser.id();
        if (!users.isActive(authorId) || (!me.equals(authorId) && blocks.isBlockedEitherWay(me, authorId))) {
            throw new NotFoundException(FollowService.USER_NOT_FOUND, "User not found.");
        }
        Instant now = clock.instant();
        List<StoryGroup> groups = group(me, stories.of(me, authorId, now), now);
        if (groups.isEmpty()) {
            UserBrief author = briefs.loadOrPlaceholder(authorId, briefs.load(List.of(authorId)));
            return new StoryGroup(author, List.of(), true, false, authorId.equals(me));
        }
        return groups.get(0);
    }

    /** Marks a story seen by the caller. Seeing your own story, or one twice, is not an error. */
    @Transactional
    public void view(UUID storyId) {
        UUID me = CurrentUser.id();
        Instant now = clock.instant();
        Row story = stories.visible(me, storyId, now)
                .orElseThrow(() -> new NotFoundException(STORY_NOT_FOUND, "Story not found."));
        if (!story.authorId().equals(me)) {
            stories.markViewed(storyId, me, now);
        }
    }

    private List<StoryGroup> group(UUID me, List<Row> rows, Instant now) {
        Map<UUID, List<StoryItem>> byAuthor = new LinkedHashMap<>();
        for (Row row : rows) {
            byAuthor.computeIfAbsent(row.authorId(), k -> new ArrayList<>()).add(toItem(row, now));
        }
        Map<UUID, UserBrief> authors = briefs.load(byAuthor.keySet());
        List<StoryGroup> groups = new ArrayList<>();
        byAuthor.forEach((authorId, items) -> groups.add(new StoryGroup(briefs.loadOrPlaceholder(authorId, authors),
                items, items.stream().allMatch(StoryItem::seen), false, authorId.equals(me))));
        return groups;
    }

    private StoryItem toItem(Row row, Instant now) {
        boolean text = row.mediaKey() == null;
        String kind = text ? "text" : row.mediaKind();
        return new StoryItem(row.id(), kind, urls.urlFor(row.mediaKey()), urls.urlFor(row.posterKey()), row.body(),
                row.color(), row.createdAt(), TimeAgo.between(row.createdAt(), now), row.expiresAt(),
                row.allowReplies(), row.allowSharing(), row.seen());
    }
}
