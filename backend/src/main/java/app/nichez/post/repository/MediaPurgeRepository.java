package app.nichez.post.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The purge job's queries: media of taken-down posts whose retention has run out. */
@Repository
public class MediaPurgeRepository {

    /** One media row to purge, with its object keys (any of them may be null). */
    public record Candidate(UUID postId, UUID mediaId, Instant deletedAt, String originalKey, String finalKey,
            String posterKey) {
    }

    private final JdbcClient jdbc;

    MediaPurgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Unpurged media of posts deleted or removed before {@code cutoff}, oldest first, strictly
     * after the keyset ({@code afterDeletedAt}, {@code afterMediaId}). The keyset lets one run
     * walk past a row whose deletion failed instead of fetching it again.
     */
    public List<Candidate> findDue(Instant cutoff, Instant afterDeletedAt, UUID afterMediaId, int limit) {
        return jdbc.sql("""
                        select p.id as post_id, m.id as media_id, p.deleted_at,
                               m.original_key, m.final_key, m.poster_key
                        from posts p join media m on m.id = p.media_id
                        where p.status in ('deleted', 'removed')
                          and p.deleted_at < :cutoff
                          and m.purged_at is null
                          and (p.deleted_at, m.id) > (:afterAt, :afterId)
                        order by p.deleted_at, m.id
                        limit :limit""")
                .param("cutoff", Timestamp.from(cutoff))
                .param("afterAt", Timestamp.from(afterDeletedAt))
                .param("afterId", afterMediaId)
                .param("limit", limit)
                .query((rs, n) -> new Candidate(
                        rs.getObject("post_id", UUID.class),
                        rs.getObject("media_id", UUID.class),
                        rs.getTimestamp("deleted_at").toInstant(),
                        rs.getString("original_key"),
                        rs.getString("final_key"),
                        rs.getString("poster_key")))
                .list();
    }

    /** Stamps the media purged. Returns false if another run got there first. */
    public boolean markPurged(UUID mediaId, Instant now) {
        return jdbc.sql("update media set purged_at = :now where id = :id and purged_at is null")
                .param("now", Timestamp.from(now))
                .param("id", mediaId)
                .update() > 0;
    }
}
