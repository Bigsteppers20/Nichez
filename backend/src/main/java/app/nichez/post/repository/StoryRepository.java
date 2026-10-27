package app.nichez.post.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL over stories. A story is shown while {@code expires_at} is in the future, to everyone it
 * is not hidden from and who has no block with its author.
 */
@Repository
public class StoryRepository {

    /** A story and, when it has media, the keys to serve it. */
    public record Row(UUID id, UUID authorId, String mediaKind, String mediaKey, String posterKey, String body,
            String color, boolean allowReplies, boolean allowSharing, Instant createdAt, Instant expiresAt,
            boolean seen) {
    }

    private static final String SELECT = """
            select s.id, s.author_id, m.kind as media_kind, coalesce(m.final_key, m.original_key) as media_key,
                   m.poster_key, s.body, s.color, s.allow_replies, s.allow_sharing, s.created_at, s.expires_at,
                   exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = :me) as seen
            from stories s left join media m on m.id = s.media_id""";

    private static final org.springframework.jdbc.core.RowMapper<Row> ROW = (rs, n) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("media_kind"),
            rs.getString("media_key"), rs.getString("poster_key"), rs.getString("body"), rs.getString("color"),
            rs.getBoolean("allow_replies"), rs.getBoolean("allow_sharing"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("seen"));

    private final JdbcClient jdbc;

    StoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int countSince(UUID author, Instant since) {
        return jdbc.sql("select count(*)::int from stories where author_id = :author and created_at >= :since")
                .param("author", author)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    public UUID insert(UUID author, UUID mediaId, String body, String color, boolean allowReplies, boolean allowSharing,
            Instant now, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into stories (id, author_id, media_id, body, color, allow_replies, allow_sharing,
                                             created_at, expires_at)
                        values (:id, :author, :media, :body, :color, :replies, :sharing, :now, :expires)""")
                .param("id", id)
                .param("author", author)
                .param("media", mediaId)
                .param("body", body)
                .param("color", color)
                .param("replies", allowReplies)
                .param("sharing", allowSharing)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expiresAt))
                .update();
        return id;
    }

    public void hideFrom(UUID storyId, Collection<UUID> userIds) {
        for (UUID user : userIds) {
            jdbc.sql("insert into story_hidden_from (story_id, user_id) values (:story, :user) on conflict do nothing")
                    .param("story", storyId)
                    .param("user", user)
                    .update();
        }
    }

    /** The condition for a story the viewer {@code :me} may see; {@code s} is the story. */
    private static String visibleTo() {
        return """
                s.expires_at > :now
                and not exists (select 1 from story_hidden_from h where h.story_id = s.id and h.user_id = :me)
                and not exists (select 1 from blocks b where (b.blocker_id = :me and b.blocked_id = s.author_id)
                                or (b.blocker_id = s.author_id and b.blocked_id = :me))
                and not exists (select 1 from profiles ap where ap.id = s.author_id
                                and (ap.deactivated_at is not null or ap.deletion_requested_at is not null
                                     or ap.deleted_at is not null))""";
    }

    /** Live stories from the caller and the people they follow (not muted), oldest first per person. */
    public List<Row> feed(UUID me, Instant now) {
        return jdbc.sql(SELECT + " where " + visibleTo() + """

                          and (s.author_id = :me
                               or (s.author_id in (select f.followee_id from follows f where f.follower_id = :me)
                                   and not exists (select 1 from mutes mu where mu.muter_id = :me
                                                   and mu.muted_id = s.author_id)))
                        order by s.created_at, s.id""")
                .param("me", me)
                .param("now", Timestamp.from(now))
                .query(ROW)
                .list();
    }

    /** One person's live stories that the viewer may see, oldest first. */
    public List<Row> of(UUID me, UUID author, Instant now) {
        return jdbc.sql(SELECT + " where s.author_id = :author and " + visibleTo() + " order by s.created_at, s.id")
                .param("me", me)
                .param("author", author)
                .param("now", Timestamp.from(now))
                .query(ROW)
                .list();
    }

    public Optional<Row> visible(UUID me, UUID storyId, Instant now) {
        return jdbc.sql(SELECT + " where s.id = :id and " + visibleTo())
                .param("me", me)
                .param("id", storyId)
                .param("now", Timestamp.from(now))
                .query(ROW)
                .optional();
    }

    public void markViewed(UUID storyId, UUID viewer, Instant now) {
        jdbc.sql("""
                        insert into story_views (story_id, viewer_id, viewed_at) values (:story, :viewer, :now)
                        on conflict do nothing""")
                .param("story", storyId)
                .param("viewer", viewer)
                .param("now", Timestamp.from(now))
                .update();
    }
}
