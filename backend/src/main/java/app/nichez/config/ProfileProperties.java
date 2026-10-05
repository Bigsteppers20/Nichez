package app.nichez.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Profile and handle settings.
 * Env: PROFILE_HANDLE_CHECK_PER_MINUTE.
 *
 * <p>{@code handleCheckPerMinute} is per signed-in user. The count is kept in memory,
 * so with several instances each one allows that many.
 */
@Validated
@ConfigurationProperties("profile")
public record ProfileProperties(@Positive int handleCheckPerMinute) {
}
