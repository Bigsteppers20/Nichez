package app.nichez.common;

/** Builds {@code LIKE} patterns from what a person typed, so {@code %} and {@code _} in it are plain characters. */
public final class SqlLike {

    private SqlLike() {
    }

    /** {@code %text%}, for use with {@code ilike :pattern escape '\'}. Null or blank input gives null. */
    public static String contains(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.isEmpty() ? null : "%" + escape(trimmed) + "%";
    }

    /** {@code text%}: matches names that start with what was typed. */
    public static String startsWith(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.isEmpty() ? null : escape(trimmed) + "%";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
