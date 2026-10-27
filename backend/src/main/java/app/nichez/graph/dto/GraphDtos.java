package app.nichez.graph.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Bodies for following people and the lists around it. */
public final class GraphDtos {

    private GraphDtos() {
    }

    /** After a follow or unfollow: whether the caller follows them now, and how many follow them. */
    public record FollowState(@JsonProperty("isFollowing") boolean isFollowing, int followers) {
    }

    /** Follow several people at once (onboarding). Up to 50; people who cannot be followed are skipped. */
    public record FollowManyRequest(@NotEmpty @Size(max = 50) List<@NotNull UUID> userIds) {
    }

    public record FollowManyResponse(int followed) {
    }

    /** A person in a followers, following or mutual list. */
    public record PersonItem(
            UUID id,
            String name,
            String handle,
            String avatarUrl,
            boolean verified,
            @JsonProperty("isFollowing") boolean isFollowing,
            @JsonProperty("isYou") boolean isYou) {
    }

    /** Shaped like the app's {@code SuggestedCreator}: {@code niche} is why they are suggested. */
    public record SuggestedCreator(
            UUID id,
            String name,
            String handle,
            String niche,
            String imageUrl,
            boolean following) {
    }
}
