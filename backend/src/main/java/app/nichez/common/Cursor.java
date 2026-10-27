package app.nichez.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * The opaque {@code cursor} of a list response: the sort key of the last item, joined with
 * {@code |} and base64url-encoded. Clients pass it back untouched. A cursor that does not decode
 * is a 400 {@code INVALID_CURSOR}; it is never trusted to be well formed.
 */
public final class Cursor {

    public static final String INVALID_CURSOR = "INVALID_CURSOR";

    private Cursor() {
    }

    public static String encode(Object... parts) {
        String joined = Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining("|"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /** The parts of a cursor, or null when the client sent none. Exactly {@code size} parts, else 400. */
    public static List<String> decode(String cursor, int size) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            List<String> parts = List.of(raw.split("\\|", -1));
            if (parts.size() != size) {
                throw invalid();
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    public static Instant instant(String part) {
        try {
            return Instant.parse(part);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    public static UUID uuid(String part) {
        try {
            return UUID.fromString(part);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    public static long number(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_CURSOR, "The cursor is not valid.");
    }

    /** The most common key: a time and an id, newest first. */
    public record TimeId(Instant time, UUID id) {

        /** Null when the client sent no cursor. */
        public static TimeId decode(String cursor) {
            List<String> parts = Cursor.decode(cursor, 2);
            return parts == null ? null : new TimeId(instant(parts.get(0)), uuid(parts.get(1)));
        }

        public String encode() {
            return Cursor.encode(time, id);
        }
    }
}
