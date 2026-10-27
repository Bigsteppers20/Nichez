package app.nichez.interaction.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds {@code @handle} mentions in a comment. Handles are 3 to 30 letters, digits, dots and underscores. */
final class MentionParser {

    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_.@])@([A-Za-z0-9_.]{3,30})");

    private MentionParser() {
    }

    /** Distinct handles in lower case, in order of appearance, at most {@code max}. */
    static Set<String> handles(String body, int max) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find() && found.size() < max) {
            String handle = matcher.group(1);
            // A trailing dot is the end of a sentence, not part of the handle.
            while (handle.endsWith(".")) {
                handle = handle.substring(0, handle.length() - 1);
            }
            if (handle.length() >= 3) {
                found.add(handle.toLowerCase(Locale.ROOT));
            }
        }
        return found;
    }
}
