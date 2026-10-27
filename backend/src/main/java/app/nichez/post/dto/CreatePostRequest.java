package app.nichez.post.dto;

import app.nichez.post.entity.Post;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code audience} defaults to {@code everyone} and {@code allowComments} to true when left out.
 * {@code taggedUserIds} may hold at most {@link #MAX_TAGS} people. {@code communityId} posts into a
 * community the caller belongs to; {@code nicheId} must then be that community's home niche.
 */
public record CreatePostRequest(
        @NotNull UUID mediaId,
        @Size(max = Post.MAX_CAPTION_LENGTH) String caption,
        @NotNull UUID nicheId,
        @Pattern(regexp = "everyone|niche_members", message = "must be everyone or niche_members") String audience,
        Boolean allowComments,
        @Size(max = MAX_TAGS) List<@NotNull UUID> taggedUserIds,
        UUID communityId) {

    public static final int MAX_TAGS = 10;
}
