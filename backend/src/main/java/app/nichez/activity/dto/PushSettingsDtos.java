package app.nichez.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies for push devices and settings. */
public final class PushSettingsDtos {

    private PushSettingsDtos() {
    }

    /** {@code HH:mm}, 24-hour. */
    public static final String TIME = "([01]\\d|2[0-3]):[0-5]\\d";

    /** Which pushes the caller gets. Quiet hours are stored; they are applied once a time zone is known. */
    public record Settings(
            boolean pushReactions,
            boolean pushComments,
            boolean pushFollows,
            boolean pushMentions,
            boolean pushReshares,
            boolean quietHoursEnabled,
            String quietHoursStart,
            String quietHoursEnd) {
    }

    /** Every field is optional; what is left out stays as it is. */
    public record UpdateSettings(
            Boolean pushReactions,
            Boolean pushComments,
            Boolean pushFollows,
            Boolean pushMentions,
            Boolean pushReshares,
            Boolean quietHoursEnabled,
            @Pattern(regexp = TIME, message = "must be HH:mm") String quietHoursStart,
            @Pattern(regexp = TIME, message = "must be HH:mm") String quietHoursEnd) {
    }

    public record RegisterDevice(
            @NotBlank @Size(min = 8, max = 4096) String token,
            @NotBlank @Pattern(regexp = "android|ios|web", message = "must be android, ios or web") String platform) {
    }

    public record UnreadCount(int count) {
    }
}
