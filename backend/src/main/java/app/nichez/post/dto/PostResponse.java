package app.nichez.post.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * One post, shaped like the app's {@code FeedPost}: {@code niche} is the label it shows, with
 * {@code nicheId} beside it. {@code mediaUrl} and {@code posterUrl} are short-lived signed URLs;
 * {@code posterUrl} is null for images and until a video's poster frame exists.
 * {@code timeAgo} is computed from {@code createdAt}. Flow 5 adds topComment and the per-viewer fields.
 */
public record PostResponse(
        UUID id,
        Author author,
        UUID nicheId,
        String niche,
        String caption,
        String audience,
        boolean allowComments,
        String status,
        @JsonProperty("isVideo") boolean isVideo,
        String mediaUrl,
        String posterUrl,
        boolean sensitive,
        String sensitiveReason,
        int reactions,
        int comments,
        Instant createdAt,
        String timeAgo) {

    public record Author(UUID id, String name, String handle, String avatarUrl) {
    }
}
