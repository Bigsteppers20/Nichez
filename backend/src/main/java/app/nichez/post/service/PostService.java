package app.nichez.post.service;

import app.nichez.common.AuditService;
import app.nichez.common.NotFoundException;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.niche.service.NicheStatsService;
import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostStatus;
import app.nichez.post.repository.PostRepository;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a post down. Who is asking always comes from the verified token ({@link CurrentUser}).
 */
@Service
public class PostService {

    public static final String POST_NOT_FOUND = "POST_NOT_FOUND";
    public static final String POST_DELETED_EVENT = "POST_DELETED";
    public static final String POST_REMOVED_EVENT = "POST_REMOVED";

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** Why the caller may take the post down. The author wins when they are also a moderator or admin. */
    private enum ActorRole {
        AUTHOR, MODERATOR, ADMIN
    }

    private final PostRepository posts;
    private final NicheRepository niches;
    private final NicheStatsService nicheStats;
    private final AuditService audit;
    private final Clock clock;

    PostService(PostRepository posts, NicheRepository niches, NicheStatsService nicheStats, AuditService audit,
            Clock clock) {
        this.posts = posts;
        this.niches = niches;
        this.nicheStats = nicheStats;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Soft-deletes a post. The author's delete makes it DELETED; a moderator of its niche or an
     * admin makes it REMOVED. Anyone else, an unknown id and a post that is already gone all get
     * the same 404, so the response never reveals that a post exists.
     *
     * <p>The row is locked first, so two deletes at once cannot both act: the second sees the
     * post gone and gets 404. The author's post count is moved by a trigger on {@code posts};
     * the audit row commits with the status change or not at all.
     */
    @Transactional
    public void delete(UUID postId, String ipAddress) {
        UUID me = CurrentUser.id();
        Post post = posts.findByIdForUpdate(postId)
                .filter(p -> p.getStatus() != PostStatus.DELETED && p.getStatus() != PostStatus.REMOVED)
                .orElseThrow(PostService::notFound);
        ActorRole role = roleOf(me, post);

        PostStatus previous = post.getStatus();
        Instant now = clock.instant();
        if (role == ActorRole.AUTHOR) {
            post.markDeleted(now);
        } else {
            post.markRemoved(now);
        }
        posts.saveAndFlush(post);

        audit.record(role == ActorRole.AUTHOR ? POST_DELETED_EVENT : POST_REMOVED_EVENT, ipAddress, Map.of(
                "postId", post.getId().toString(),
                "nicheId", post.getNicheId().toString(),
                "authorId", post.getAuthorId().toString(),
                "actorId", me.toString(),
                "actorRole", role.name().toLowerCase(Locale.ROOT),
                "previousStatus", previous.dbValue()));
        if (previous == PostStatus.PUBLISHED) {
            nicheStats.evict(post.getNicheId());
        }
        log.info("Post taken down, post={}, actorRole={}", post.getId(), role);
    }

    private ActorRole roleOf(UUID me, Post post) {
        if (post.getAuthorId().equals(me)) {
            return ActorRole.AUTHOR;
        }
        if (niches.isModerator(post.getNicheId(), me)) {
            return ActorRole.MODERATOR;
        }
        if (CurrentUser.isAdmin()) {
            return ActorRole.ADMIN;
        }
        throw notFound();
    }

    private static NotFoundException notFound() {
        return new NotFoundException(POST_NOT_FOUND, "Post not found.");
    }
}
