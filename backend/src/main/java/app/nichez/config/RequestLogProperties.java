package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The API request log kept in the database (see {@code request_logs}).
 * Env: REQUEST_LOG_ENABLED, REQUEST_LOG_FLUSH_INTERVAL, REQUEST_LOG_BATCH_SIZE, REQUEST_LOG_QUEUE_CAPACITY,
 * REQUEST_LOG_RETENTION, REQUEST_LOG_PURGE_CRON.
 *
 * <p>Requests are queued in memory and written every {@code flushInterval}, at most {@code batchSize}
 * rows per insert. When {@code queueCapacity} requests are waiting (the database is down or slow) the
 * newest are dropped rather than slowing the API. Rows older than {@code retention} are deleted by a
 * daily job; {@code purgeCron} is a Spring cron expression in UTC.
 */
@Validated
@ConfigurationProperties("request-log")
public record RequestLogProperties(
        boolean enabled,
        @NotNull @DurationMin(seconds = 1) Duration flushInterval,
        @Positive int batchSize,
        @Positive int queueCapacity,
        @NotNull @DurationMin(days = 1) Duration retention,
        @NotBlank String purgeCron) {
}
