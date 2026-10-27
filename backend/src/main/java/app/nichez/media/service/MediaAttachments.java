package app.nichez.media.service;

import app.nichez.common.ConflictException;
import app.nichez.common.NotFoundException;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaKind;
import app.nichez.media.entity.MediaStatus;
import app.nichez.media.repository.MediaRepository;
import app.nichez.media.service.MediaObjectStore.StoredObject;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a file the caller just uploaded (through {@code POST /uploads/sign}) and makes it usable
 * for something that needs no processing, such as a profile photo or a story. Posts do not use
 * this: they hand their media to the processing queue instead.
 *
 * <p>The stored file is checked first, outside a transaction. The row is then locked and checked
 * again, so two requests cannot both claim the same upload.
 */
@Service
public class MediaAttachments {

    public static final String MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND";
    public static final String MEDIA_ALREADY_USED = "MEDIA_ALREADY_USED";

    private final MediaRepository media;
    private final MediaVerifier verifier;
    private final TransactionTemplate transaction;

    MediaAttachments(MediaRepository media, MediaVerifier verifier, TransactionTemplate transaction) {
        this.media = media;
        this.verifier = verifier;
        this.transaction = transaction;
    }

    /**
     * Marks the caller's upload ready, served from the file as uploaded.
     *
     * @throws NotFoundException 404 {@code MEDIA_NOT_FOUND}: no such media, or someone else's
     * @throws ConflictException 409 {@code MEDIA_ALREADY_USED}: it is already behind a post or another attachment
     */
    public Media attach(UUID mediaId, UUID owner, Set<MediaKind> allowedKinds) {
        Media upload = media.findByIdAndOwnerId(mediaId, owner).orElseThrow(MediaAttachments::notFound);
        if (!isUnused(upload)) {
            throw alreadyUsed();
        }
        if (!allowedKinds.contains(upload.getKind())) {
            throw UploadService.unsupportedType();
        }
        StoredObject stored = verifier.verifyStored(upload);
        return transaction.execute(status -> {
            Media locked = media.findByIdAndOwnerIdForUpdate(mediaId, owner).orElseThrow(MediaAttachments::notFound);
            if (!isUnused(locked)) {
                throw alreadyUsed();
            }
            locked.setBytes(stored.bytes());
            locked.setFinalKey(locked.getOriginalKey());
            locked.setStatus(MediaStatus.READY);
            return media.saveAndFlush(locked);
        });
    }

    private static boolean isUnused(Media candidate) {
        return candidate.getStatus() == MediaStatus.PENDING || candidate.getStatus() == MediaStatus.UPLOADED;
    }

    private static NotFoundException notFound() {
        return new NotFoundException(MEDIA_NOT_FOUND, "Media not found.");
    }

    private static ConflictException alreadyUsed() {
        return new ConflictException(MEDIA_ALREADY_USED, "This media is already in use.");
    }
}
