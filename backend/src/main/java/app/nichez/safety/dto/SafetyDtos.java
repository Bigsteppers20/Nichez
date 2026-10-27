package app.nichez.safety.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Bodies for blocking, muting, content preferences, reports and moderator decisions. */
public final class SafetyDtos {

    private SafetyDtos() {
    }

    /** A person the caller muted or blocked, as the Muted &amp; blocked screen lists them. */
    public record RestrictionItem(UUID id, String name, String handle, String avatarUrl, String reason, Instant since) {
    }

    public record ContentPreferencesResponse(String sensitiveFilter, boolean autoHideReported) {
    }

    /** What is left out stays as it is. {@code sensitiveFilter}: {@code veil}, {@code hide} or {@code show}. */
    public record UpdateContentPreferences(
            @Pattern(regexp = "veil|hide|show", message = "must be veil, hide or show") String sensitiveFilter,
            Boolean autoHideReported) {
    }

    public record AutoHideRequest(@NotNull Boolean enabled) {
    }

    /** One entry of the report sheet. {@code severe} turns the badge red: reasons that target a person. */
    public record ReportReason(String id, String title, String subtitle, @JsonProperty("severe") boolean severe) {
    }

    public static final String TARGET_TYPES = "post|comment|user|community";
    public static final String REASONS = "hate|wrong-niche|nudity|spam|religious|other";

    /** {@code POST /posts/:id/report}. */
    public record PostReportRequest(
            @NotBlank @Pattern(regexp = REASONS, message = "must be one of " + REASONS) String reasonId,
            @Size(max = 500) String note) {
    }

    /** {@code POST /reports}: report a post, a comment, a person or a community. */
    public record ReportRequest(
            @NotBlank @Pattern(regexp = TARGET_TYPES, message = "must be one of " + TARGET_TYPES) String targetType,
            @NotNull UUID targetId,
            @NotBlank @Pattern(regexp = REASONS, message = "must be one of " + REASONS) String reasonId,
            @Size(max = 500) String note) {
    }

    /** The report was received. Posts the caller reported are hidden from them at once (unless they turned that off). */
    public record ReportReceived(UUID id) {
    }

    /** One thing reported to moderators, shaped like the app's {@code ReportItem}. */
    public record ReportItem(
            UUID id,
            String targetType,
            UUID targetId,
            String reason,
            int reportCount,
            String authorHandle,
            String excerpt,
            String mediaUrl,
            @JsonProperty("severe") boolean severe,
            String verdict) {
    }

    public record VerdictRequest(
            @NotBlank @Pattern(regexp = "keep|remove|ban", message = "must be keep, remove or ban") String verdict) {
    }

    public record VerdictResponse(UUID reportId, String verdict, int resolved) {
    }
}
