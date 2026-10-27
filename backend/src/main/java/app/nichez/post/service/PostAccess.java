package app.nichez.post.service;

import app.nichez.common.NotFoundException;
import app.nichez.post.entity.Post;
import app.nichez.post.repository.PostRepository;
import app.nichez.post.repository.PostVisibilityRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "may this person act on this post". Every refusal is the same 404
 * {@code POST_NOT_FOUND}, so a response never reveals that a post exists, is private, or is
 * hidden by a block.
 */
@Component
public class PostAccess {

    private final PostRepository posts;
    private final PostVisibilityRepository visibility;

    PostAccess(PostRepository posts, PostVisibilityRepository visibility) {
        this.posts = posts;
        this.visibility = visibility;
    }

    /** The post-detail rule (see {@link PostVisibilityRepository#canRead}). */
    public void requireReadable(UUID me, UUID postId) {
        if (!visibility.canRead(me, postId)) {
            throw notFound();
        }
    }

    /** A published post the caller can see: what reactions, comments, saves and shares need. */
    public Post requireInteractable(UUID me, UUID postId) {
        if (!visibility.canInteract(me, postId)) {
            throw notFound();
        }
        return posts.findById(postId).orElseThrow(PostAccess::notFound);
    }

    public static NotFoundException notFound() {
        return new NotFoundException(PostService.POST_NOT_FOUND, "Post not found.");
    }
}
