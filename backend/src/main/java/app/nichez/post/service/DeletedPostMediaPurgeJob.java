package app.nichez.post.service;

import app.nichez.common.AuditService;
import app.nichez.config.PostProperties;
import app.nichez.config.StorageProperties;
import app.nichez.post.repository.MediaPurgeRepository;
import app.nichez.post.repository.MediaPurgeRepository.Candidate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * Daily: deletes the stored files of posts that were deleted or removed more than
 * {@code posts.media-retention} (30 days) ago. The post and media rows stay; the media row is
 * stamped {@code purged_at}.
 *
 * <p>Objects are deleted first and the row stamped after, so a crash in between only means the
 * next run deletes the same keys again, which S3 treats as success. That also makes two
 * instances running at once harmless. A media whose deletion fails is logged and retried the
 * next day.
 */
@Component
public class DeletedPostMediaPurgeJob {

    public static final String MEDIA_PURGED_EVENT = "MEDIA_PURGED";

    private static final Logger log = LoggerFactory.getLogger(DeletedPostMediaPurgeJob.class);
    private static final UUID LOWEST_ID = new UUID(0, 0);

    private final MediaPurgeRepository repository;
    private final S3Client s3;
    private final StorageProperties storage;
    private final PostProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    DeletedPostMediaPurgeJob(MediaPurgeRepository repository, S3Client s3, StorageProperties storage,
            PostProperties properties, AuditService audit, TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.s3 = s3;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(cron = "${posts.purge-cron}", zone = "UTC")
    void scheduled() {
        run();
    }

    /** One full pass. Returns how many media were purged. */
    public int run() {
        Instant cutoff = clock.instant().minus(properties.mediaRetention());
        Instant afterAt = Instant.EPOCH;
        UUID afterId = LOWEST_ID;
        int purged = 0;
        int failed = 0;
        while (true) {
            List<Candidate> batch = repository.findDue(cutoff, afterAt, afterId, properties.purgeBatchSize());
            for (Candidate candidate : batch) {
                if (purge(candidate)) {
                    purged++;
                } else {
                    failed++;
                }
            }
            if (batch.size() < properties.purgeBatchSize()) {
                break;
            }
            Candidate last = batch.get(batch.size() - 1);
            afterAt = last.deletedAt();
            afterId = last.mediaId();
        }
        if (purged > 0 || failed > 0) {
            log.info("Media purge finished, purged={}, failed={}", purged, failed);
        }
        return purged;
    }

    private boolean purge(Candidate candidate) {
        try {
            Stream.of(candidate.originalKey(), candidate.finalKey(), candidate.posterKey())
                    .filter(key -> key != null && !key.isBlank())
                    .distinct()
                    .forEach(key -> s3.deleteObject(
                            DeleteObjectRequest.builder().bucket(storage.bucket()).key(key).build()));
        } catch (SdkException e) {
            // The exception text can carry the bucket and key; log only ids.
            log.warn("Media purge failed, media={}, post={}, error={}", candidate.mediaId(), candidate.postId(),
                    e.getClass().getSimpleName());
            return false;
        }
        transaction.executeWithoutResult(status -> {
            if (repository.markPurged(candidate.mediaId(), clock.instant())) {
                audit.record(MEDIA_PURGED_EVENT, null, Map.of(
                        "postId", candidate.postId().toString(),
                        "mediaId", candidate.mediaId().toString()));
            }
        });
        return true;
    }
}
