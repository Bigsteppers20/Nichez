package app.nichez.media.service;

import app.nichez.common.ApiException;
import app.nichez.common.ConflictException;
import app.nichez.config.UploadProperties;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.UploadType;
import app.nichez.media.service.MediaObjectStore.StoredObject;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Looks at the file actually stored for a media row: it must exist, fit its kind's size cap, and
 * really be the type it was declared as. The declared type comes from the client and is never trusted.
 */
@Component
public class MediaVerifier {

    public static final String MEDIA_NOT_UPLOADED = "MEDIA_NOT_UPLOADED";
    public static final String MEDIA_TYPE_MISMATCH = "MEDIA_TYPE_MISMATCH";

    private final MediaObjectStore store;
    private final UploadProperties uploadProperties;

    MediaVerifier(MediaObjectStore store, UploadProperties uploadProperties) {
        this.store = store;
        this.uploadProperties = uploadProperties;
    }

    /**
     * Throws 409 {@code MEDIA_NOT_UPLOADED}, 413 {@code MEDIA_TOO_LARGE} or 400
     * {@code MEDIA_TYPE_MISMATCH}; 502 {@code STORAGE_UNAVAILABLE} when storage cannot answer.
     * Make this call outside any database transaction: it waits on storage.
     */
    public StoredObject verifyStored(Media upload) {
        StoredObject stored = store.inspect(upload.getOriginalKey(), UploadType.SNIFF_BYTES)
                .orElseThrow(() -> new ConflictException(MEDIA_NOT_UPLOADED, "The file has not been uploaded yet."));
        if (stored.bytes() > uploadProperties.maxBytes(upload.getKind())) {
            throw UploadService.tooLarge();
        }
        Optional<UploadType> declared = UploadType.fromMimeType(upload.getDeclaredType());
        Optional<UploadType> actual = UploadType.sniff(stored.head());
        if (actual.isEmpty() || !actual.equals(declared)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MEDIA_TYPE_MISMATCH,
                    "The uploaded file is not the type it was declared as.");
        }
        return stored;
    }
}
