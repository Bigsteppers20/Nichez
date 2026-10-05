package app.nichez.niche.service;

import java.time.Duration;

/**
 * How long a new post can expect to stay at the top of Fresh, worded for G4:
 * "Yours is at the top of Fresh for {@code <phrase>}."
 *
 * <p>Fresh is newest first, so a post holds the top until the next one arrives. The expected
 * wait is the average gap between posts over the last 24 hours: 24 h divided by the number of
 * posts in that time. With no other posts the wait is a day or more.
 */
public final class FreshWindow {

    static final Duration PERIOD = Duration.ofHours(24);

    private FreshWindow() {
    }

    public static String forPostsInLastDay(long posts) {
        long minutes = (long) Math.ceil(PERIOD.toMinutes() / (double) Math.max(posts, 1));
        if (minutes <= 1) {
            return "the next minute";
        }
        if (minutes < 15) {
            return "the next " + minutes + " minutes";
        }
        if (minutes < 60) {
            // Round to five minutes: "the next 25 minutes", never "the next 27 minutes".
            long rounded = Math.min(55, Math.round(minutes / 5.0) * 5);
            return "the next " + rounded + " minutes";
        }
        if (minutes < 120) {
            return "the next hour";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "the next " + hours + " hours";
        }
        return "the next day";
    }
}
