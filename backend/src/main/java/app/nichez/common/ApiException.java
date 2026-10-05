package app.nichez.common;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * A failure the client is meant to see. {@code code} is the stable contract
 * (UPPER_SNAKE, listed in docs/API_ERRORS.md); {@code message} is shown to the
 * client, so it must never contain secrets or another user's data.
 *
 * <p>A feature may attach extra top-level fields to the error body with
 * {@link #withProperty} (for example {@code attemptsLeft}) and ask for a
 * {@code Retry-After} header with {@link #withRetryAfter}. Values must be as safe as the message.
 */
public class ApiException extends RuntimeException {

    private static final Pattern UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private Duration retryAfter;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        if (!UPPER_SNAKE.matcher(code).matches()) {
            throw new IllegalArgumentException("Error code must be UPPER_SNAKE: " + code);
        }
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra fields for the error body, in insertion order. */
    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }

    /** How long the client should wait before retrying, or null. Sent as {@code Retry-After}. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public ApiException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ApiException withRetryAfter(Duration retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }
}
