package app.nichez.sharing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies for sharing a post out, reshaping it into another niche, and public links. */
public final class SharingDtos {

    private SharingDtos() {
    }

    /** {@code link}, {@code send}, {@code cross_post} or {@code save_image}: how the post left the app. */
    public record ShareRequest(
            @NotBlank @Pattern(regexp = "link|send|cross_post|save_image",
                    message = "must be link, send, cross_post or save_image") String channel) {
    }

    public record ShareCount(int shares) {
    }

    /** Share into another niche. The caller must have joined it. */
    public record CrossPostRequest(@NotNull UUID nicheId) {
    }

    public record CrossPostResponse(UUID postId, UUID nicheId, int shares) {
    }

    public record ReshareResponse(@JsonProperty("reshared") boolean reshared, int reshares) {
    }

    /** The public address of a post, for Copy link. */
    public record LinkResponse(String url) {
    }

    /**
     * What anyone can see at a share link, signed in or not: only posts that are published, open to
     * everyone, and not in a private community. Nothing about the viewer.
     */
    public record PublicPost(
            UUID id,
            String authorName,
            String authorHandle,
            String authorAvatarUrl,
            String niche,
            String caption,
            @JsonProperty("isVideo") boolean isVideo,
            String mediaUrl,
            String posterUrl,
            int reactions,
            int comments,
            Instant createdAt,
            String timeAgo) {
    }
}
