package app.nichez.common;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429. The handler sends a Retry-After header. */
public class RateLimitedException extends ApiException {

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "Too many requests. Try again later.");
        withRetryAfter(retryAfter);
    }
}
