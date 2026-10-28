package app.nichez.post.service;

import app.nichez.common.AuditService;
import app.nichez.config.PostProperties;
import app.nichez.post.repository.PostPublishRepository;
import app.nichez.post.repository.PostPublishRepository.Job;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes new posts live. Creating a post queues its media in {@code media_processing_jobs} and
 * leaves the post {@code processing}; with no transcoding or poster frames yet, this job is the
 * whole of "processing": it marks the media ready and publishes the post.
 *
 * <p>Each job runs in its own transaction, claimed with a conditional update, so two instances
 * never publish the same post and one bad job does not hold up the rest. A job that throws goes
 * back in the queue a minute later. A post deleted or removed while it waited is not revived.
 *
 * <p>Switch off with {@code posts.publish-enabled=false} when a real media pipeline consumes the
 * queue instead.
 */
@Component
@ConditionalOnProperty(prefix = "posts", name = "publish-enabled", havingValue = "true")
public class PostPublishJob {

    public static final String POST_PUBLISHED_EVENT = "POST_PUBLISHED";

    private static final Logger log = LoggerFactory.getLogger(PostPublishJob.class);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final PostPublishRepository repository;
    private final PostProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PostPublishJob(PostPublishRepository repository, PostProperties properties, AuditService audit,
            TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${posts.publish-interval}")
    void scheduled() {
        run();
    }

    /** Works through every due job. Returns how many posts it published. */
    public int run() {
        int published = 0;
        while (true) {
            List<Job> batch = repository.findDue(clock.instant(), properties.publishBatchSize());
            boolean failed = false;
            for (Job job : batch) {
                try {
                    if (Boolean.TRUE.equals(transaction.execute(status -> process(job)))) {
                        published++;
                    }
                } catch (RuntimeException e) {
                    failed = true;
                    // Ids only: the exception text can carry SQL parameters.
                    log.warn("Publishing failed, post={}, media={}, error={}", job.postId(), job.mediaId(),
                            e.getClass().getSimpleName());
                    requeue(job);
                }
            }
            // A failure ends the pass: the job is due again in a minute, and looping would only
            // fetch it again while the database is unwell.
            if (failed || batch.size() < properties.publishBatchSize()) {
                break;
            }
        }
        if (published > 0) {
            log.info("Posts published, count={}", published);
        }
        return published;
    }

    /** One job, inside its transaction. True if a post went live. */
    private boolean process(Job job) {
        Instant now = clock.instant();
        if (!repository.claim(job.mediaId(), now)) {
            return false;
        }
        boolean live = repository.publish(job.postId(), now);
        if (live) {
            repository.markMediaReady(job.mediaId());
            audit.record(POST_PUBLISHED_EVENT, null, Map.of(
                    "postId", job.postId().toString(),
                    "mediaId", job.mediaId().toString()));
        }
        repository.complete(job.mediaId());
        return live;
    }

    private void requeue(Job job) {
        try {
            transaction.executeWithoutResult(status -> repository.retryAt(job.mediaId(),
                    clock.instant().plus(RETRY_DELAY)));
        } catch (RuntimeException e) {
            log.warn("Could not requeue publishing job, media={}, error={}", job.mediaId(),
                    e.getClass().getSimpleName());
        }
    }
}
