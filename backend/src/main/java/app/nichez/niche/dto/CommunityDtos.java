package app.nichez.niche.dto;

import app.nichez.profile.dto.UserBrief;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies for communities: groups inside a home niche, public or private. */
public final class CommunityDtos {

    private CommunityDtos() {
    }

    /**
     * A community, shaped like the app's {@code Community}. {@code joined}, {@code requested} and
     * {@code myRole} are about the caller. {@code memberFaces} is only on the profile, not in lists.
     */
    public record CommunityResponse(
            UUID id,
            String name,
            String description,
            int members,
            String emoji,
            String coverColor,
            String iconUrl,
            UUID homeNicheId,
            String homeNiche,
            @JsonProperty("isPrivate") boolean isPrivate,
            boolean joined,
            boolean requested,
            String myRole,
            List<UserBrief> memberFaces) {
    }

    public record CreateCommunityRequest(
            @NotBlank @Size(min = 2, max = 40) String name,
            @Size(max = 300) String description,
            @Size(min = 1, max = 16) String emoji,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must look like #E8EEF4") String coverColor,
            @NotNull UUID homeNicheId,
            Boolean isPrivate,
            @Size(max = 10) List<@NotBlank @Size(max = 200) String> rules) {
    }

    /** {@code status}: {@code joined}, {@code requested} (private community, waiting for a moderator) or {@code none}. */
    public record JoinState(String status, int members) {
    }

    /** A member of a community, or a person waiting to join ({@code role} null). */
    public record MemberItem(UUID id, String name, String handle, String avatarUrl, boolean verified, String role,
            Instant since, @JsonProperty("isYou") boolean isYou) {
    }

    public record ChangeRoleRequest(
            @NotBlank @Pattern(regexp = "moderator|member", message = "must be moderator or member") String role) {
    }

    public record InviteRequest(@NotEmpty @Size(max = 50) List<@NotNull UUID> userIds) {
    }

    public record InviteResult(int invited) {
    }

    public record RulesResponse(List<String> rules) {
    }
}
