package app.nichez.profile.service;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a handle may look like. Pure functions, no database: the caller supplies the
 * reserved and verified handles to compare against.
 *
 * <ul>
 *   <li>Characters: letters, digits, underscore, dot. Length 3 to 30. At least one letter or digit.</li>
 *   <li>Reserved: equal to a reserved handle, ignoring case.</li>
 *   <li>Lookalike: has the same {@link #skeleton} as a reserved or verified handle, so
 *       {@code Adm1n}, {@code ad.min} and {@code adrnin} cannot pass as {@code admin}.</li>
 * </ul>
 */
public final class HandleRules {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    public enum Violation {
        /** Wrong characters, wrong length, or nothing but dots and underscores. */
        INVALID,
        /** Reserved, or a lookalike of a reserved or verified handle. */
        RESERVED
    }

    private static final Pattern FORMAT = Pattern.compile("[a-zA-Z0-9_.]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");
    private static final Pattern HAS_LETTER_OR_DIGIT = Pattern.compile(".*[a-zA-Z0-9].*");

    private HandleRules() {
    }

    public static boolean isWellFormed(String handle) {
        return handle != null && FORMAT.matcher(handle).matches() && HAS_LETTER_OR_DIGIT.matcher(handle).matches();
    }

    /**
     * The first rule {@code handle} breaks, or empty if it may be claimed (taken-ness is a
     * separate question, answered by the database).
     *
     * @param reserved handles nobody may claim
     * @param verified handles of verified accounts; the caller leaves out the claimant's own
     */
    public static Optional<Violation> check(String handle, Collection<String> reserved, Collection<String> verified) {
        if (!isWellFormed(handle)) {
            return Optional.of(Violation.INVALID);
        }
        for (String r : reserved) {
            if (r.equalsIgnoreCase(handle)) {
                return Optional.of(Violation.RESERVED);
            }
        }
        String skeleton = skeleton(handle);
        for (String other : reserved) {
            if (skeleton(other).equals(skeleton)) {
                return Optional.of(Violation.RESERVED);
            }
        }
        for (String other : verified) {
            if (skeleton(other).equals(skeleton)) {
                return Optional.of(Violation.RESERVED);
            }
        }
        return Optional.empty();
    }

    /**
     * Folds a handle to what it looks like: lower case, then 0 to o, 1 to l, 5 to s, dots and
     * underscores dropped, and rn to m (after the drop, so {@code r.n} counts too).
     *
     * <p>Capital I, lowercase i, lowercase l and 1 all fold to l. Folding only a capital I
     * (before lowercasing) leaves a hole: {@code AD.MIN} would become {@code admln} and no
     * longer match {@code admin}. Treating i like l closes it, and also catches {@code adm1n}.
     */
    public static String skeleton(String handle) {
        String lowered = handle.toLowerCase(Locale.ROOT);
        StringBuilder folded = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            switch (c) {
                case '.', '_' -> {
                }
                case '0' -> folded.append('o');
                case '1', 'i' -> folded.append('l');
                case '5' -> folded.append('s');
                default -> folded.append(c);
            }
        }
        return folded.toString().replace("rn", "m");
    }
}
