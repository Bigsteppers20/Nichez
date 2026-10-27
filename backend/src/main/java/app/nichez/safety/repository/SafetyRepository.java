package app.nichez.safety.repository;

import app.nichez.common.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over blocks, mutes and content preferences. */
@Repository
public class SafetyRepository {

    /** Someone the caller blocked or muted, and since when. */
    public record Entry(UUID userId, Instant at) {
    }

    /** A person's content filters. A person with no row has the defaults. */
    public record ContentPreferences(String sensitiveFilter, boolean autoHideReported) {

        public static ContentPreferences defaults() {
            return new ContentPreferences("veil", true);
        }
    }

    private final JdbcClient jdbc;

    SafetyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void block(UUID blocker, UUID blocked, Instant now) {
        jdbc.sql("insert into blocks (blocker_id, blocked_id, created_at) values (:a, :b, :now) on conflict do nothing")
                .param("a", blocker)
                .param("b", blocked)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void unblock(UUID blocker, UUID blocked) {
        jdbc.sql("delete from blocks where blocker_id = :a and blocked_id = :b")
                .param("a", blocker)
                .param("b", blocked)
                .update();
    }

    public void mute(UUID muter, UUID muted, Instant now) {
        jdbc.sql("insert into mutes (muter_id, muted_id, created_at) values (:a, :b, :now) on conflict do nothing")
                .param("a", muter)
                .param("b", muted)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void unmute(UUID muter, UUID muted) {
        jdbc.sql("delete from mutes where muter_id = :a and muted_id = :b")
                .param("a", muter)
                .param("b", muted)
                .update();
    }

    /** The people the caller blocked, newest first. */
    public List<Entry> blocked(UUID me, Cursor.TimeId after, int fetch) {
        return entries("blocks", "blocker_id", "blocked_id", me, after, fetch);
    }

    /** The people the caller muted, newest first. */
    public List<Entry> muted(UUID me, Cursor.TimeId after, int fetch) {
        return entries("mutes", "muter_id", "muted_id", me, after, fetch);
    }

    // The table and column names are constants chosen by the two callers above, never request input.
    private List<Entry> entries(String table, String ownerColumn, String otherColumn, UUID me, Cursor.TimeId after,
            int fetch) {
        return jdbc.sql("""
                        select %2$s as user_id, created_at as at from %1$s
                        where %3$s = :me
                          and (cast(:t as timestamptz) is null
                               or (created_at, %2$s) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by created_at desc, %2$s desc
                        limit :fetch""".formatted(table, otherColumn, ownerColumn))
                .param("me", me)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query((rs, n) -> new Entry(rs.getObject("user_id", UUID.class), rs.getTimestamp("at").toInstant()))
                .list();
    }

    public ContentPreferences preferences(UUID userId) {
        return jdbc.sql("select sensitive_filter, auto_hide_reported from content_preferences where user_id = :id")
                .param("id", userId)
                .query((rs, n) -> new ContentPreferences(rs.getString(1), rs.getBoolean(2)))
                .optional()
                .orElseGet(ContentPreferences::defaults);
    }

    public void savePreferences(UUID userId, ContentPreferences preferences, Instant now) {
        jdbc.sql("""
                        insert into content_preferences (user_id, sensitive_filter, auto_hide_reported, updated_at)
                        values (:id, :filter, :hide, :now)
                        on conflict (user_id) do update set sensitive_filter = excluded.sensitive_filter,
                            auto_hide_reported = excluded.auto_hide_reported""")
                .param("id", userId)
                .param("filter", preferences.sensitiveFilter())
                .param("hide", preferences.autoHideReported())
                .param("now", Timestamp.from(now))
                .update();
    }
}
