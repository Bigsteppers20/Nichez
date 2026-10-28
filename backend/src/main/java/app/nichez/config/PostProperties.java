package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Post settings.
 * Env: POSTS_MEDIA_RETENTION, POSTS_PURGE_CRON, POSTS_PURGE_BATCH_SIZE, POSTS_CREATE_PER_HOUR,
 * POSTS_PUBLISH_ENABLED, POSTS_PUBLISH_INTERVAL, POSTS_PUBLISH_BATCH_SIZE.
 *
 * <p>{@code mediaRetention} is how long the stored files of a deleted or removed post are kept
 * before the purge job deletes them. {@code purgeCron} is a Spring cron expression in UTC.
 * {@code createPerHour} is per signed-in user, counted in memory per instance.
 *
 * <p>{@code publishEnabled} turns on the in-process publisher, which takes queued media straight to
 * ready and publishes its post (there is no transcoding yet). Switch it off when a real media
 * pipeline consumes the queue instead. {@code publishInterval} is the pause between passes.
 */
@Validated
@ConfigurationProperties("posts")
public record PostProperties(
        @NotNull Duration mediaRetention,
        @NotBlank String purgeCron,
        @Positive int purgeBatchSize,
        @Positive int createPerHour,
        boolean publishEnabled,
        @NotNull @DurationMin(seconds = 1) Duration publishInterval,
        @Positive int publishBatchSize) {
}
