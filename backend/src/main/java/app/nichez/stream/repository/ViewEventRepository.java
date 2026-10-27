package app.nichez.stream.repository;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code view_events}. Counters and daily totals are kept by a trigger on insert. */
@Repository
public class ViewEventRepository {

    private final JdbcClient jdbc;

    ViewEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a view that qualifies. Returns false when this person already has a qualifying view of
     * this post today (the unique index decides, so two simultaneous reports cannot both count).
     */
    public boolean insertQualifying(UUID postId, UUID viewerId, int watchedMs, LocalDate day) {
        return jdbc.sql("""
                        insert into view_events (post_id, viewer_id, watched_ms, qualifies, day)
                        values (:post, :viewer, :ms, true, :day)
                        on conflict (post_id, viewer_id, day) where qualifies do nothing""")
                .param("post", postId)
                .param("viewer", viewerId)
                .param("ms", watchedMs)
                .param("day", java.sql.Date.valueOf(day))
                .update() > 0;
    }

    /** Records a playback that does not count, with the reason. */
    public void insertRejected(UUID postId, UUID viewerId, int watchedMs, LocalDate day, String reason) {
        jdbc.sql("""
                        insert into view_events (post_id, viewer_id, watched_ms, qualifies, reject_reason, day)
                        values (:post, :viewer, :ms, false, :reason, :day)""")
                .param("post", postId)
                .param("viewer", viewerId)
                .param("ms", watchedMs)
                .param("reason", reason)
                .param("day", java.sql.Date.valueOf(day))
                .update();
    }
}
