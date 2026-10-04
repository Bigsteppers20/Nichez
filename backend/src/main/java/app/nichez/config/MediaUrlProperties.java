package app.nichez.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How clients get media URLs.
 * Env: MEDIA_URL_PROVIDER, MEDIA_URL_TTL, WORKER_BASE_URL, PLAYBACK_TOKEN_SECRET.
 *
 * <p>{@code presigned}: S3 presigned URLs straight to the bucket (local dev).
 * {@code worker}: short-lived tokens verified by the Cloudflare playback Worker;
 * only then are {@code workerBaseUrl} and {@code tokenSecret} required.
 */
@Validated
@ConfigurationProperties("media")
public record MediaUrlProperties(
        @NotNull UrlProvider urlProvider,
        @NotNull Duration ttl,
        String workerBaseUrl,
        String tokenSecret) {

    public enum UrlProvider { PRESIGNED, WORKER }

    static final int MIN_SECRET_LENGTH = 32;
    /** S3 SigV4 presigned URLs cannot outlive 7 days. */
    static final Duration MAX_TTL = Duration.ofDays(7);

    @AssertTrue(message = "media.ttl (MEDIA_URL_TTL) must be greater than 0 and at most 7d")
    public boolean isTtlInRange() {
        return ttl == null || (ttl.isPositive() && ttl.compareTo(MAX_TTL) <= 0);
    }

    @AssertTrue(message = "media.worker-base-url (WORKER_BASE_URL) is required, as an http(s) URL, "
            + "when media.url-provider=worker")
    public boolean isWorkerBaseUrlSetForWorker() {
        return urlProvider != UrlProvider.WORKER
                || (workerBaseUrl != null && workerBaseUrl.matches("https?://\\S+"));
    }

    @AssertTrue(message = "media.token-secret (PLAYBACK_TOKEN_SECRET) is required, at least "
            + MIN_SECRET_LENGTH + " characters, when media.url-provider=worker")
    public boolean isTokenSecretSetForWorker() {
        return urlProvider != UrlProvider.WORKER
                || (tokenSecret != null && tokenSecret.length() >= MIN_SECRET_LENGTH);
    }

    @Override
    public String toString() {
        return "MediaUrlProperties[urlProvider=" + urlProvider + ", ttl=" + ttl
                + ", workerBaseUrl=<set>, tokenSecret=<redacted>]";
    }
}
