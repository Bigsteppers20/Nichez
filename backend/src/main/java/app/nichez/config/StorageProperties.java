package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3-compatible object storage: Cloudflare R2 in prod, MinIO locally.
 * Env: STORAGE_ENDPOINT, STORAGE_PUBLIC_ENDPOINT, STORAGE_REGION,
 * STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY, STORAGE_BUCKET.
 *
 * <p>{@code endpoint} is what the backend itself calls (HEAD, DELETE).
 * {@code publicEndpoint} is the host baked into presigned URLs, so it must be
 * reachable by the client (e.g. 10.0.2.2:9000 from the Android emulator).
 */
@Validated
@ConfigurationProperties("storage")
public record StorageProperties(
        @NotBlank
        @Pattern(regexp = "^(https?://\\S+)?$", message = "must be an http(s) URL")
        String endpoint,
        @NotBlank
        @Pattern(regexp = "^(https?://\\S+)?$", message = "must be an http(s) URL")
        String publicEndpoint,
        @NotBlank String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket) {

    public URI endpointUri() {
        return URI.create(endpoint);
    }

    public URI publicEndpointUri() {
        return URI.create(publicEndpoint);
    }

    @Override
    public String toString() {
        return "StorageProperties[endpoint=<set>, publicEndpoint=<set>, region=" + region
                + ", accessKey=<redacted>, secretKey=<redacted>, bucket=" + bucket + "]";
    }
}
