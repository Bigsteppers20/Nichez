package app.nichez.profile.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies for the signed-in user's own account and for reading profiles. */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    /**
     * A profile, shaped like the app's {@code UserProfile}. {@code isFollowing} and {@code isYou}
     * are about the caller. Never carries a phone number (CLAUDE.md rule 11).
     */
    public record UserProfileResponse(
            UUID id,
            String name,
            String handle,
            String bio,
            String avatarUrl,
            String coverUrl,
            boolean verified,
            int followers,
            int following,
            int postCount,
            long totalViews,
            List<String> niches,
            @JsonProperty("isFollowing") boolean isFollowing,
            @JsonProperty("isYou") boolean isYou) {
    }

    /** How far the caller got through sign-up; decides where the app lands after sign-in. */
    public record Onboarding(boolean hasHandle, boolean hasNiches, boolean hasPhoto, boolean onboarded) {
    }

    public record MeResponse(UserProfileResponse profile, Onboarding onboarding) {
    }

    /**
     * What the app needs at boot. {@code deletionScheduledFor} is set while the account is waiting to
     * be erased. {@code reactivated} is true when this call brought a deactivated account back.
     */
    public record SessionResponse(
            UUID userId,
            boolean hasHandle,
            boolean hasNiches,
            boolean hasPhoto,
            boolean onboarded,
            @JsonProperty("isAdmin") boolean isAdmin,
            boolean reactivated,
            Instant deletionScheduledFor) {
    }

    /** Every field is optional; what is left out stays as it is. An empty {@code bio} clears it. */
    public record UpdateProfileRequest(
            @Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            @Size(max = 300) String bio,
            @Size(max = 30) String handle) {
    }

    /** The id from {@code POST /uploads/sign}, once the file has been PUT to the upload URL. */
    public record SetImageRequest(@NotNull UUID mediaId) {
    }
}
