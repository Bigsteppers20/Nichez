package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Account deletion.
 * Env: ACCOUNT_DELETION_GRACE, ACCOUNT_DELETION_CRON, ACCOUNT_DELETION_BATCH_SIZE.
 *
 * <p>A deleted account is hidden at once and erased once {@code deletionGrace} has passed.
 * {@code deletionCron} is a Spring cron expression in UTC.
 */
@Validated
@ConfigurationProperties("account")
public record AccountProperties(
        @NotNull Duration deletionGrace,
        @NotBlank String deletionCron,
        @Positive int deletionBatchSize) {
}
