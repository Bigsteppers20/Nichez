package app.nichez.common;

import java.time.Duration;
import java.time.Instant;

/**
 * The compact relative time the app shows ("now", "40m", "2h", "3d", "2w", "1y"). Sent as
 * {@code timeAgo} next to the instant it was computed from.
 */
public final class TimeAgo {

    private TimeAgo() {
    }

    public static String between(Instant then, Instant now) {
        long seconds = Math.max(0, Duration.between(then, now).toSeconds());
        if (seconds < 60) {
            return "now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        long days = hours / 24;
        if (days < 7) {
            return days + "d";
        }
        if (days < 365) {
            return (days / 7) + "w";
        }
        return (days / 365) + "y";
    }
}
