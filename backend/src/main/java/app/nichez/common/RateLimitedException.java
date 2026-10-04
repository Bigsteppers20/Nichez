package app.nichez.common;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429. The handler sends a Retry-After header. */
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "Too many requests. Try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
