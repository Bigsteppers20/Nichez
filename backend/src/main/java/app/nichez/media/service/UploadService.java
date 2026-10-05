package app.nichez.media.service;

import app.nichez.common.ApiException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.UploadProperties;
import app.nichez.media.dto.SignUploadResponse;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.UploadType;
import app.nichez.media.repository.MediaRepository;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out direct-to-storage upload URLs. Each one belongs to a new {@link Media} row owned by
 * the caller, so a later {@code POST /posts} can only use media its author uploaded.
 */
@Service
public class UploadService {

    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String MEDIA_TOO_LARGE = "MEDIA_TOO_LARGE";

    private final MediaRepository media;
    private final ProfileRepository profiles;
    private final MediaObjectStore store;
    private final SlidingWindowRateLimiter limiter;
    private final UploadProperties properties;
    private final Clock clock;

    UploadService(MediaRepository media, ProfileRepository profiles, MediaObjectStore store,
            SlidingWindowRateLimiter limiter, UploadProperties properties, Clock clock) {
        this.media = media;
        this.profiles = profiles;
        this.store = store;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a pending media row and returns a presigned PUT for it, valid for
     * {@code uploads.url-ttl}. The type must be allowed (400 {@code UNSUPPORTED_MEDIA_TYPE}) and
     * the size within its kind's cap (413 {@code MEDIA_TOO_LARGE}).
     */
    @Transactional
    public SignUploadResponse sign(String contentType, long bytes) {
        UUID me = CurrentUser.id();
        limiter.check("upload-sign:" + me, properties.signPerHour(), Duration.ofHours(1));

        UploadType type = properties.allowed(contentType).orElseThrow(UploadService::unsupportedType);
        if (bytes > properties.maxBytes(type.kind())) {
            throw tooLarge();
        }

        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        UUID mediaId = UUID.randomUUID();
        String key = "uploads/" + me + "/" + mediaId;
        Media row = new Media(mediaId, me, type.kind(), type.mimeType(), key, now);
        row.setBytes(bytes);
        media.save(row);

        String url = store.presignPut(key, type.mimeType(), bytes, properties.urlTtl()).toString();
        return new SignUploadResponse(url, mediaId);
    }

    public static ApiException unsupportedType() {
        return new ApiException(HttpStatus.BAD_REQUEST, UNSUPPORTED_MEDIA_TYPE, "This file type is not supported.");
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, MEDIA_TOO_LARGE, "This file is too large.");
    }
}
