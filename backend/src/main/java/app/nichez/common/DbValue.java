package app.nichez.common;

import java.util.Locale;

/**
 * An enum stored as lower-case text, as in {@code status text CHECK (status IN ('ready', ...))}.
 * The default value is the constant's name in lower case ({@code NICHE_MEMBERS} is
 * {@code niche_members}), so most enums only need {@code implements DbValue}.
 * Pair it with a {@link DbEnumConverter} subclass.
 */
public interface DbValue {

    String name();

    default String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
