package app.nichez.interaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies for reactions, comments and playback reports. */
public final class InteractionDtos {

    private InteractionDtos() {
    }

    /** The six reactions the app offers. */
    public static final String REACTION_KINDS = "love|laugh|fire|cry|clap|dead";

    public record ReactionRequest(
            @NotBlank @Pattern(regexp = REACTION_KINDS, message = "must be one of " + REACTION_KINDS) String kind) {
    }

    /** {@code kind} is optional for comments: a bare PUT is a "love". */
    public record CommentReactionRequest(
            @Pattern(regexp = REACTION_KINDS, message = "must be one of " + REACTION_KINDS) String kind) {
    }

    /** The caller's reaction to a post after the change, and the post's total. {@code myReaction} is null once cleared. */
    public record PostReactionResponse(String myReaction, int reactions) {
    }

    public record CommentReactionResponse(String myReaction, Integer score) {
    }

    public record CreateCommentRequest(
            @NotBlank @Size(max = 1000) String body,
            UUID parentId) {
    }

    /**
     * One comment, shaped like the app's {@code Comment}. {@code score} is null until someone reacts.
     * Replies carry {@code isReply: true} and their top-level comment's id in {@code parentId}; they
     * follow that comment in the list.
     */
    public record CommentResponse(
            UUID id,
            UUID authorId,
            String authorName,
            String authorHandle,
            String avatarUrl,
            String body,
            Instant createdAt,
            String timeAgo,
            Integer score,
            @JsonProperty("isReply") boolean isReply,
            UUID parentId,
            @JsonProperty("isMine") boolean isMine,
            String myReaction) {
    }

    public record ViewReport(
            @NotNull UUID postId,
            @Min(0) @Max(86_400_000) int watchedMs) {
    }

    /** A batch of playbacks the app measured. The server decides which count. */
    public record ViewsRequest(@NotEmpty @Valid List<@NotNull @Valid ViewReport> views) {
    }

    public record ViewsResponse(int received, int qualified) {
    }
}
