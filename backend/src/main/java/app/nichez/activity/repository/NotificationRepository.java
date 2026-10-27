package app.nichez.activity.repository;

import app.nichez.common.Cursor;
import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code notifications}: what happened to a person, newest first. */
@Repository
public class NotificationRepository {

    /** One entry in a person's activity list. */
    public record Row(UUID id, UUID actorId, String kind, UUID postId, UUID commentId, String message,
            boolean unread, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that {@code actor} did something to {@code recipient}'s content. Reacting, following
     * and resharing notify once per person and post (a unique index), however often they are toggled.
     * Returns false when nothing was written.
     */
    public boolean insert(UUID recipient, UUID actor, String kind, UUID postId, UUID commentId, UUID nicheId,
            String message, Instant now) {
        return jdbc.sql("""
                        insert into notifications (user_id, actor_id, kind, post_id, comment_id, niche_id, message, created_at)
                        values (:user, :actor, :kind, :post, :comment, :niche, :message, :now)
                        on conflict do nothing""")
                .param("user", recipient)
                .param("actor", actor)
                .param("kind", kind)
                .param("post", postId)
                .param("comment", commentId)
                .param("niche", nicheId)
                .param("message", message)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    /** {@code kinds} limits the list (null means every kind). Fetches {@code fetch} rows after the cursor. */
    public List<Row> list(UUID me, List<String> kinds, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select n.id, n.actor_id, n.kind, n.post_id, n.comment_id, n.message, n.read_at is null as unread,
                               n.created_at
                        from notifications n
                        where n.user_id = :me
                          and (cast(:kinds as text[]) is null or n.kind = any(cast(:kinds as text[])))
                          and (n.actor_id is null or (%s and %s))
                          and (cast(:t as timestamptz) is null or (n.created_at, n.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by n.created_at desc, n.id desc
                        limit :fetch""".formatted(PostSql.notBlocked("n.actor_id"), PostSql.accountActive("n.actor_id")))
                .param("me", me)
                .param("kinds", kinds == null ? null : kinds.toArray(new String[0]))
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("kind"), rs.getObject("post_id", UUID.class),
                        rs.getObject("comment_id", UUID.class), rs.getString("message"), rs.getBoolean("unread"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public int unreadCount(UUID me) {
        return jdbc.sql("""
                        select count(*)::int from notifications n
                        where n.user_id = :me and n.read_at is null
                          and (n.actor_id is null or (%s and %s))""".formatted(
                        PostSql.notBlocked("n.actor_id"), PostSql.accountActive("n.actor_id")))
                .param("me", me)
                .query(Integer.class)
                .single();
    }

    public int markAllRead(UUID me, Instant now) {
        return jdbc.sql("update notifications set read_at = :now where user_id = :me and read_at is null")
                .param("me", me)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** True when the notification is the caller's (already read or not). */
    public boolean markRead(UUID me, UUID id, Instant now) {
        int updated = jdbc.sql("""
                        update notifications set read_at = coalesce(read_at, :now) where id = :id and user_id = :me""")
                .param("id", id)
                .param("me", me)
                .param("now", Timestamp.from(now))
                .update();
        return updated > 0;
    }
}
