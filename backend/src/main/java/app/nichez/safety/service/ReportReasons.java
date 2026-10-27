package app.nichez.safety.service;

import app.nichez.safety.dto.SafetyDtos.ReportReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The reasons offered when reporting. They mirror the app's {@code ReportReason.defaultsFor}, with the
 * niche name worked into "wrong niche", and "religious disrespect" offered only in faith niches.
 */
public final class ReportReasons {

    private static final Map<String, String> TITLES = Map.of(
            "hate", "Hate or harassment",
            "wrong-niche", "Wrong niche",
            "nudity", "Nudity or gore",
            "spam", "Spam or scam",
            "religious", "Religious disrespect",
            "other", "Something else");

    private ReportReasons() {
    }

    /** Reasons that target a person turn the badge red in the moderation queue. */
    public static boolean severe(String reasonId) {
        return "hate".equals(reasonId);
    }

    public static String title(String reasonId) {
        return TITLES.getOrDefault(reasonId, reasonId);
    }

    /** {@code nicheName} may be null (reasons not tied to a niche); {@code faith} says it is a faith niche. */
    public static List<ReportReason> forNiche(String nicheName, boolean faith) {
        List<ReportReason> reasons = new ArrayList<>();
        reasons.add(new ReportReason("hate", TITLES.get("hate"), "Targets a person or a group", true));
        reasons.add(new ReportReason("wrong-niche", TITLES.get("wrong-niche"),
                nicheName == null ? "Doesn't belong in this niche" : "Off-topic for " + nicheName, false));
        reasons.add(new ReportReason("nudity", TITLES.get("nudity"), "Sexual or graphic content", false));
        reasons.add(new ReportReason("spam", TITLES.get("spam"), "Bot behaviour, fake links", false));
        if (faith) {
            reasons.add(new ReportReason("religious", TITLES.get("religious"), "Mocking beliefs in a faith niche", false));
        }
        reasons.add(new ReportReason("other", TITLES.get("other"), "Tell us in your own words", false));
        return List.copyOf(reasons);
    }
}
