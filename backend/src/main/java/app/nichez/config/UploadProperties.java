package app.nichez.config;

import app.nichez.media.entity.MediaKind;
import app.nichez.media.entity.UploadType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Upload settings.
 * Env: UPLOADS_ALLOWED_TYPES, UPLOADS_MAX_IMAGE_SIZE, UPLOADS_MAX_VIDEO_SIZE, UPLOADS_URL_TTL,
 * UPLOADS_SIGN_PER_HOUR.
 *
 * <p>{@code allowedTypes} are content types, each of which must be one the server can recognise
 * from the file's bytes ({@link UploadType}). {@code urlTtl} is how long a presigned upload URL
 * works. {@code signPerHour} is per signed-in user, counted in memory per instance.
 */
@Validated
@ConfigurationProperties("uploads")
public record UploadProperties(
        @NotEmpty List<String> allowedTypes,
        @NotNull DataSize maxImageSize,
        @NotNull DataSize maxVideoSize,
        @NotNull Duration urlTtl,
        @Positive int signPerHour) {

    /** S3 SigV4 presigned URLs cannot outlive 7 days. */
    static final Duration MAX_URL_TTL = Duration.ofDays(7);

    @AssertTrue(message = "uploads.allowed-types (UPLOADS_ALLOWED_TYPES) may only list image/jpeg, image/png, "
            + "image/gif, image/webp, video/mp4 and video/quicktime")
    public boolean isEveryAllowedTypeRecognised() {
        return allowedTypes == null || allowedTypes.stream().allMatch(t -> UploadType.fromMimeType(t).isPresent());
    }

    @AssertTrue(message = "uploads.max-image-size and uploads.max-video-size must be greater than 0")
    public boolean isMaxSizePositive() {
        return (maxImageSize == null || maxImageSize.toBytes() > 0)
                && (maxVideoSize == null || maxVideoSize.toBytes() > 0);
    }

    @AssertTrue(message = "uploads.url-ttl (UPLOADS_URL_TTL) must be greater than 0 and at most 7d")
    public boolean isUrlTtlInRange() {
        return urlTtl == null || (urlTtl.isPositive() && urlTtl.compareTo(MAX_URL_TTL) <= 0);
    }

    /** The type for {@code contentType} if it is allowed, else empty. */
    public Optional<UploadType> allowed(String contentType) {
        return UploadType.fromMimeType(contentType)
                .filter(type -> allowedTypes.stream().anyMatch(a -> UploadType.fromMimeType(a).orElse(null) == type));
    }

    public long maxBytes(MediaKind kind) {
        return (kind == MediaKind.VIDEO ? maxVideoSize : maxImageSize).toBytes();
    }
}
