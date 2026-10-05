package app.nichez.media.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code media_processing_jobs} queue. Writes join the caller's transaction. */
@Repository
public class MediaProcessingJobRepository {

    private final JdbcClient jdbc;

    MediaProcessingJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues the media for processing, due at once. One job per media: a second enqueue fails the insert. */
    public void enqueue(UUID mediaId, UUID postId, Instant now) {
        jdbc.sql("""
                        insert into media_processing_jobs (media_id, post_id, status, available_at, created_at, updated_at)
                        values (:media, :post, 'queued', :now, :now, :now)""")
                .param("media", mediaId)
                .param("post", postId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
