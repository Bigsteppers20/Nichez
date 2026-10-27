package app.nichez.interaction.service;

import app.nichez.activity.service.Notifier;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.SocialProperties;
import app.nichez.interaction.dto.InteractionDtos.PostReactionResponse;
import app.nichez.interaction.repository.ReactionRepository;
import app.nichez.post.entity.Post;
import app.nichez.post.service.PostAccess;
import app.nichez.security.CurrentUser;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacting to a post: one of six kinds, one per person. Who is asking always comes from the
 * token, and the post must be one the caller can see (404 {@code POST_NOT_FOUND} otherwise).
 */
@Service
public class ReactionService {

    private final ReactionRepository reactions;
    private final PostAccess access;
    private final Notifier notifier;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;

    ReactionService(ReactionRepository reactions, PostAccess access, Notifier notifier,
            SlidingWindowRateLimiter limiter, SocialProperties social) {
        this.reactions = reactions;
        this.access = access;
        this.notifier = notifier;
        this.limiter = limiter;
        this.social = social;
    }

    /** Sets the caller's reaction, replacing any earlier one. The author is told once per person and post. */
    @Transactional
    public PostReactionResponse react(UUID postId, String kind) {
        UUID me = CurrentUser.id();
        limiter.check("react:" + me, social.reactionsPerMinute(), Duration.ofMinutes(1));
        Post post = access.requireInteractable(me, postId);
        reactions.set(postId, me, kind);
        notifier.reacted(me, post, kind);
        return new PostReactionResponse(kind, reactions.count(postId));
    }

    /** Clears the caller's reaction. Clearing when there is none is not an error. */
    @Transactional
    public PostReactionResponse clear(UUID postId) {
        UUID me = CurrentUser.id();
        limiter.check("react:" + me, social.reactionsPerMinute(), Duration.ofMinutes(1));
        access.requireInteractable(me, postId);
        reactions.clear(postId, me);
        return new PostReactionResponse(null, reactions.count(postId));
    }
}
