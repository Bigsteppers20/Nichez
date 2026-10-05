package app.nichez.post.service;

import app.nichez.common.NotFoundException;
import app.nichez.media.entity.Media;
import app.nichez.media.repository.MediaRepository;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.entity.Audience;
import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostStatus;
import app.nichez.post.repository.PostRepository;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading one post, with who may see it decided here. */
@Service
public class PostQueryService {

    private final PostRepository posts;
    private final MediaRepository media;
    private final NicheRepository niches;
    private final BlockPolicy blocks;
    private final PostViews views;

    PostQueryService(PostRepository posts, MediaRepository media, NicheRepository niches, BlockPolicy blocks,
            PostViews views) {
        this.posts = posts;
        this.media = media;
        this.niches = niches;
        this.blocks = blocks;
        this.views = views;
    }

    /**
     * The author sees their post while it is processing, published or failed. Anyone else sees it
     * only once published, only if they are a member of its niche when its audience is
     * {@code niche_members}, and only if neither has blocked the other. Every other case is the
     * same 404 {@code POST_NOT_FOUND}, so the response never reveals that a post exists.
     */
    @Transactional(readOnly = true)
    public PostResponse get(UUID postId) {
        UUID me = CurrentUser.id();
        Post post = posts.findById(postId).filter(p -> canSee(me, p)).orElseThrow(PostQueryService::notFound);
        Media file = media.findById(post.getMediaId()).orElseThrow(PostQueryService::notFound);
        return views.of(post, file);
    }

    private boolean canSee(UUID me, Post post) {
        if (post.getStatus() == PostStatus.DELETED || post.getStatus() == PostStatus.REMOVED) {
            return false;
        }
        if (post.getAuthorId().equals(me)) {
            return true;
        }
        return post.getStatus() == PostStatus.PUBLISHED
                && (post.getAudience() != Audience.NICHE_MEMBERS || niches.isMember(post.getNicheId(), me))
                && !blocks.isBlockedEitherWay(me, post.getAuthorId());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(PostService.POST_NOT_FOUND, "Post not found.");
    }
}
