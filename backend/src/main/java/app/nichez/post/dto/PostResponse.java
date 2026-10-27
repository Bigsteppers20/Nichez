package app.nichez.post.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One post, shaped like the app's {@code FeedPost}: {@code niche} is the label it shows, with
 * {@code nicheId} beside it. {@code mediaUrl} and {@code posterUrl} are short-lived signed URLs;
 * {@code posterUrl} is null for images and until a video's poster frame exists.
 * {@code timeAgo} is computed from {@code createdAt}.
 *
 * <p>{@code myReaction}, {@code saved} and {@code isMine} are about the caller and always computed
 * from the signed-in session. {@code reactionCounts} is the breakdown by kind (only kinds with
 * at least one). {@code resharedBy} appears only in the home feed, when the post is there because a
 * person the caller follows reshared it; {@code topComment} only on {@code GET /posts/:id}.
 */
public record PostResponse(
        UUID id,
        Author author,
        UUID nicheId,
        String niche,
        UUID communityId,
        String caption,
        String audience,
        boolean allowComments,
        String status,
        @JsonProperty("isVideo") boolean isVideo,
        boolean hasAudio,
        String mediaUrl,
        String posterUrl,
        boolean sensitive,
        String sensitiveReason,
        int reactions,
        int comments,
        int reshares,
        int shares,
        int saves,
        long views,
        Map<String, Integer> reactionCounts,
        String myReaction,
        boolean saved,
        @JsonProperty("isMine") boolean isMine,
        ResharedBy resharedBy,
        TopComment topComment,
        Instant createdAt,
        String timeAgo) {

    public record Author(UUID id, String name, String handle, String avatarUrl) {
    }

    public record ResharedBy(UUID id, String name, String handle) {
    }

    public record TopComment(UUID id, String authorName, String body, int score, String timeAgo) {
    }

    public PostResponse withResharedBy(ResharedBy value) {
        return new PostResponse(id, author, nicheId, niche, communityId, caption, audience, allowComments, status,
                isVideo, hasAudio, mediaUrl, posterUrl, sensitive, sensitiveReason, reactions, comments, reshares,
                shares, saves, views, reactionCounts, myReaction, saved, isMine, value, topComment, createdAt,
                timeAgo);
    }

    public PostResponse withTopComment(TopComment value) {
        return new PostResponse(id, author, nicheId, niche, communityId, caption, audience, allowComments, status,
                isVideo, hasAudio, mediaUrl, posterUrl, sensitive, sensitiveReason, reactions, comments, reshares,
                shares, saves, views, reactionCounts, myReaction, saved, isMine, resharedBy, value, createdAt,
                timeAgo);
    }
}
