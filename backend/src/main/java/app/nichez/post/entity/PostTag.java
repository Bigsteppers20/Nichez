package app.nichez.post.entity;

import app.nichez.common.AssignedIdEntity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A person tagged in a post. */
@Entity
@Table(name = "post_tags")
public class PostTag extends AssignedIdEntity<PostTagId> {

    @EmbeddedId
    private PostTagId id;

    protected PostTag() {
    }

    public PostTag(UUID postId, UUID userId) {
        this.id = new PostTagId(postId, userId);
    }

    @Override
    public PostTagId getId() {
        return id;
    }

    public UUID getPostId() {
        return id.postId();
    }

    public UUID getUserId() {
        return id.userId();
    }
}
