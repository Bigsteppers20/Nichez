package app.nichez.post.service;

import app.nichez.common.TimeAgo;
import app.nichez.interaction.repository.CommentRepository;
import app.nichez.post.dto.PostResponse;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading one post, with who may see it decided in {@link PostAccess}. */
@Service
public class PostQueryService {

    private final PostAccess access;
    private final PostCards cards;
    private final CommentRepository comments;
    private final Clock clock;

    PostQueryService(PostAccess access, PostCards cards, CommentRepository comments, Clock clock) {
        this.access = access;
        this.cards = cards;
        this.comments = comments;
        this.clock = clock;
    }

    /**
     * The author sees their post while it is processing, published or failed. Anyone else sees it
     * only once published, only if they are a member of its niche when its audience is
     * {@code niche_members}, only if it is not in a private community they have not joined, and
     * only if neither has blocked the other. Every other case is the same 404
     * {@code POST_NOT_FOUND}, so the response never reveals that a post exists.
     */
    @Transactional(readOnly = true)
    public PostResponse get(UUID postId) {
        UUID me = app.nichez.security.CurrentUser.id();
        access.requireReadable(me, postId);
        PostResponse post = cards.loadOne(me, postId).orElseThrow(PostAccess::notFound);
        return post.withTopComment(comments.top(me, postId)
                .map(c -> new PostResponse.TopComment(c.id(), c.authorName(), c.body(), c.score(),
                        TimeAgo.between(c.createdAt(), clock.instant())))
                .orElse(null));
    }
}
