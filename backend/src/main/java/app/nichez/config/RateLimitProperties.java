package app.nichez.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client request limits.
 * Env: RATE_LIMIT_ENABLED, RATE_LIMIT_REQUESTS_PER_MINUTE, RATE_LIMIT_BURST.
 */
@Validated
@ConfigurationProperties("rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Positive int requestsPerMinute,
        @Positive int burst) {
}
