package app.nichez.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Primary key of a tag: the post and the tagged user. */
@Embeddable
public record PostTagId(
        @Column(name = "post_id") UUID postId,
        @Column(name = "user_id") UUID userId) implements Serializable {
}
