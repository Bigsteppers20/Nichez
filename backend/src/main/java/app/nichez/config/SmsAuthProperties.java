package app.nichez.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phone OTP sign-in limits. Every number here is a config value.
 * Env: AUTH_SMS_ALLOWED_COUNTRIES, AUTH_SMS_MAX_SENDS_PER_PHONE_PER_HOUR,
 * AUTH_SMS_MAX_SENDS_PER_IP_PER_HOUR, AUTH_SMS_RESEND_COOLDOWN, AUTH_SMS_CODE_TTL,
 * AUTH_SMS_MAX_ATTEMPTS, AUTH_SMS_LOCKOUT.
 *
 * <p>{@code allowedCountries} are ISO 3166-1 alpha-2 region codes (NG, GH, US).
 * A "send" is any SMS requested, from /start or /resend.
 */
@Validated
@ConfigurationProperties("auth.sms")
public record SmsAuthProperties(
        @NotEmpty Set<@Pattern(regexp = "^[A-Z]{2}$", message = "must be a two-letter upper-case region code") String> allowedCountries,
        @Positive int maxSendsPerPhonePerHour,
        @Positive int maxSendsPerIpPerHour,
        @NotNull Duration resendCooldown,
        @NotNull Duration codeTtl,
        @Positive int maxAttempts,
        @NotNull Duration lockout) {

    public SmsAuthProperties {
        allowedCountries = allowedCountries == null ? null : Set.copyOf(allowedCountries);
    }
}
