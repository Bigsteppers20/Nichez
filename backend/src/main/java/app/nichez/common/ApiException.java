package app.nichez.common;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * A failure the client is meant to see. {@code code} is the stable contract
 * (UPPER_SNAKE, listed in docs/API_ERRORS.md); {@code message} is shown to the
 * client, so it must never contain secrets or another user's data.
 */
public class ApiException extends RuntimeException {

    private static final Pattern UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    private final HttpStatus status;
    private final String code;

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
}
