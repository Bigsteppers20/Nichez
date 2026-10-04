package app.nichez.common;

import java.util.List;

/** Every list response: {@code { items, nextCursor }}. A null cursor means the last page. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }
}
