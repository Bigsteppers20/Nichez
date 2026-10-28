package app.nichez.post.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The publisher's queries: due jobs in {@code media_processing_jobs}, and the status changes that
 * take a processed post live. Each change is a conditional update, so a job taken or a post
 * deleted in the meantime is detected by the row count, not by a lock.
 */
@Repository
public class PostPublishRepository {

    /** One queued job: the media to process and the post waiting on it. */
    public record Job(UUID mediaId, UUID postId) {
    }

    private final JdbcClient jdbc;

    PostPublishRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queued jobs that are due, oldest first. */
    public List<Job> findDue(Instant now, int limit) {
        return jdbc.sql("""
                        select media_id, post_id
                        from media_processing_jobs
                        where status = 'queued' and available_at <= :now
                        order by available_at, media_id
                        limit :limit""")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, n) -> new Job(rs.getObject("media_id", UUID.class), rs.getObject("post_id", UUID.class)))
                .list();
    }

    /** Takes the job. Returns false if another run got there first. */
    public boolean claim(UUID mediaId, Instant now) {
        return jdbc.sql("""
                        update media_processing_jobs
                        set status = 'running', attempts = attempts + 1
                        where media_id = :id and status = 'queued' and available_at <= :now""")
                .param("id", mediaId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    /**
     * Publishes the post if it is still processing. Returns false if it was deleted or removed
     * while it waited. The profile's {@code post_count} follows by trigger.
     */
    public boolean publish(UUID postId, Instant now) {
        return jdbc.sql("""
                        update posts set status = 'published', published_at = :now
                        where id = :id and status = 'processing'""")
                .param("id", postId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    public void markMediaReady(UUID mediaId) {
        jdbc.sql("update media set status = 'ready' where id = :id and status = 'processing'")
                .param("id", mediaId)
                .update();
    }

    /** The job stays as the record of what was processed. */
    public void complete(UUID mediaId) {
        jdbc.sql("update media_processing_jobs set status = 'done' where media_id = :id")
                .param("id", mediaId)
                .update();
    }

    /** Puts a job that failed back in the queue, due at {@code retryAt}. */
    public void retryAt(UUID mediaId, Instant retryAt) {
        jdbc.sql("update media_processing_jobs set status = 'queued', available_at = :at where media_id = :id")
                .param("id", mediaId)
                .param("at", Timestamp.from(retryAt))
                .update();
    }
}
