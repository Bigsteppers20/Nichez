package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What {@code GET /config} tells the app, and the public address share links point to.
 * Env: APP_PUBLIC_BASE_URL, APP_MIN_VERSION, APP_TERMS_URL, APP_PRIVACY_URL, APP_FEATURE_LIVE,
 * APP_FEATURE_REELS, APP_FEATURE_DIRECT_MESSAGES.
 *
 * <p>{@code minAppVersion} is {@code major.minor.patch}; older apps are told to update. The feature
 * flags say which parts of the plan the server actually serves, so the app can hide the rest.
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotBlank @Pattern(regexp = "https?://[^\\s/]+(/[^\\s]*)?", message = "must be an http(s) URL") String publicBaseUrl,
        @NotBlank @Pattern(regexp = "\\d+\\.\\d+\\.\\d+", message = "must look like 1.0.0") String minAppVersion,
        @NotBlank String termsUrl,
        @NotBlank String privacyUrl,
        boolean featureLive,
        boolean featureReels,
        boolean featureDirectMessages) {

    /** The public base URL without a trailing slash. */
    public String baseUrl() {
        return publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }
}
