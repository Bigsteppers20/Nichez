package app.nichez.post.dto;

import app.nichez.post.entity.Post;
import app.nichez.profile.dto.UserBrief;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies for where to post, post status and numbers, drafts and stories. */
public final class PostExtrasDtos {

    private PostExtrasDtos() {
    }

    /** Somewhere the caller can post: {@code niche} or {@code community}. A community's {@code nicheId} is its home niche. */
    public record PostTarget(String type, UUID id, String name, UUID nicheId) {
    }

    public record PostStatusResponse(UUID id, String status) {
    }

    /**
     * The numbers behind one post, for its author. {@code weeklyChange} is null when the week before had
     * no views; {@code engagementRate} is null until the post has views. Earnings arrive with wallets.
     */
    public record PostAnalytics(
            UUID postId,
            long views,
            long viewsThisWeek,
            long viewsLastWeek,
            Double weeklyChange,
            Double engagementRate,
            int reactions,
            int comments,
            int reshares,
            int shares,
            int saves,
            Map<String, Integer> reactionCounts) {
    }

    public record AnalyticsTotals(long views, long viewsThisWeek, long viewsLastWeek, long reactions, long comments,
            long reshares, long shares, long saves, int posts, int followers) {
    }

    /** The author's totals and their posts, most viewed first. */
    public record MyAnalytics(AnalyticsTotals totals, List<PostResponse> items, String nextCursor) {
    }

    // --- drafts ------------------------------------------------------------------------

    public record DraftRequest(
            UUID mediaId,
            UUID nicheId,
            @Size(max = Post.MAX_CAPTION_LENGTH) String caption,
            @Pattern(regexp = "everyone|niche_members", message = "must be everyone or niche_members") String audience,
            Boolean allowComments) {
    }

    public record DraftResponse(UUID id, UUID mediaId, String mediaUrl, UUID nicheId, String caption, String audience,
            boolean allowComments, Instant updatedAt, String timeAgo) {
    }

    /** {@code total} is how many drafts the caller has (the Drafts tile shows it). */
    public record DraftsPage(List<DraftResponse> items, String nextCursor, int total) {
    }

    // --- stories -----------------------------------------------------------------------

    /** Post a story: a media story ({@code mediaId}) or a text story ({@code body} and {@code color}). */
    public record StoryRequest(
            UUID mediaId,
            @Size(min = 1, max = 280) String body,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must look like #1B2A41") String color,
            @Size(max = 100) List<UUID> hiddenFromUserIds,
            Boolean allowReplies,
            Boolean allowSharing) {
    }

    /** One story, shaped like the app's {@code StoryItem}. {@code kind} is video, image or text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoryItem(UUID id, String kind, String mediaUrl, String posterUrl, String text, String color,
            Instant createdAt, String postedAgo, Instant expiresAt, boolean allowReplies, boolean allowSharing,
            @JsonProperty("seen") boolean seen) {
    }

    /** A person's live stories, oldest first: the app's {@code FeedStory}. */
    public record StoryGroup(UserBrief author, List<StoryItem> items, @JsonProperty("seen") boolean seen,
            @JsonProperty("live") boolean live, @JsonProperty("isYou") boolean isYou) {
    }
}
