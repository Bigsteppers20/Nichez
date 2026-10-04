package app.nichez.config;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origins allowed to call the API. Env: CORS_ALLOWED_ORIGINS (comma-separated).
 * Empty is valid: native Flutter clients do not send CORS requests.
 */
@Validated
@ConfigurationProperties("cors")
public record CorsProperties(
        List<@Pattern(
                regexp = "https?://[^/*\\s]+",
                message = "must be an exact origin like https://app.example.com (no wildcard, path or trailing slash)")
                String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
