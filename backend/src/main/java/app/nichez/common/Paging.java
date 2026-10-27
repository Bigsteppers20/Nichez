package app.nichez.common;

import java.util.List;
import java.util.function.Function;

/**
 * Page size rules for every list. A repository fetches {@code limit + 1} rows; the extra row
 * only tells {@link #hasMore} there is a next page, and is never returned.
 */
public final class Paging {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private Paging() {
    }

    /** The requested page size, defaulting to 20 and never above 50 or below 1. */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    public static <T> boolean hasMore(List<T> fetched, int limit) {
        return fetched.size() > limit;
    }

    /** The rows that belong to the page. */
    public static <T> List<T> head(List<T> fetched, int limit) {
        return hasMore(fetched, limit) ? fetched.subList(0, limit) : fetched;
    }

    /** The cursor for the page after this one, or null on the last page. */
    public static <T> String nextCursor(List<T> fetched, int limit, Function<T, String> cursorOf) {
        return hasMore(fetched, limit) ? cursorOf.apply(fetched.get(limit - 1)) : null;
    }
}
