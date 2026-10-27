package app.nichez.interaction.repository;

import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code comments} and {@code comment_reactions}. Blocked and deactivated authors are left out of every list. */
@Repository
public class CommentRepository {

    /** A comment with the caller's reaction to it. */
    public record Row(UUID id, UUID postId, UUID authorId, UUID parentId, String body, int score, Instant createdAt,
            String myReaction) {
    }

    /** What a write needs to know about a comment. */
    public record Basic(UUID id, UUID postId, UUID authorId, UUID parentId, boolean deleted) {
    }

    /** The comment shown under a post on its detail page. */
    public record Top(UUID id, String authorName, String body, int score, Instant createdAt) {
    }

    private static final String COLUMNS = """
            c.id, c.post_id, c.author_id, c.parent_id, c.body, c.reaction_count, c.created_at, r.kind as my_reaction""";
    private static final String JOINS = " from comments c left join comment_reactions r on r.comment_id = c.id and r.user_id = :me ";
    private static final String LIVE_AND_VISIBLE = " c.deleted_at is null and %s and %s ".formatted(
            PostSql.notBlocked("c.author_id"), PostSql.accountActive("c.author_id"));

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getObject("id", UUID.class),
            rs.getObject("post_id", UUID.class), rs.getObject("author_id", UUID.class),
            rs.getObject("parent_id", UUID.class), rs.getString("body"), rs.getInt("reaction_count"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("my_reaction"));

    private final JdbcClient jdbc;

    CommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID postId, UUID authorId, UUID parentId, String body, Instant now) {
        jdbc.sql("""
                        insert into comments (id, post_id, author_id, parent_id, body, created_at)
                        values (:id, :post, :author, :parent, :body, :now)""")
                .param("id", id)
                .param("post", postId)
                .param("author", authorId)
                .param("parent", parentId)
                .param("body", body)
                .param("now", Timestamp.from(now))
                .update();
    }

    public Optional<Basic> find(UUID id) {
        return jdbc.sql("select id, post_id, author_id, parent_id, deleted_at is not null as deleted from comments where id = :id")
                .param("id", id)
                .query((rs, n) -> new Basic(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                        rs.getObject("author_id", UUID.class), rs.getObject("parent_id", UUID.class),
                        rs.getBoolean("deleted")))
                .optional();
    }

    public Optional<Row> one(UUID me, UUID id) {
        return jdbc.sql("select " + COLUMNS + JOINS + " where c.id = :id")
                .param("me", me)
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** Newest first. {@code after} is the last comment of the previous page, or null. */
    public List<Row> newest(UUID me, UUID postId, Instant afterTime, UUID afterId, int fetch) {
        return jdbc.sql("select " + COLUMNS + JOINS + """
                        where c.post_id = :post and c.parent_id is null and""" + LIVE_AND_VISIBLE + """
                          and (cast(:t as timestamptz) is null
                               or (c.created_at, c.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by c.created_at desc, c.id desc
                        limit :fetch""")
                .param("me", me)
                .param("post", postId)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query(ROW)
                .list();
    }

    /** Best first: most reactions, then newest. */
    public List<Row> best(UUID me, UUID postId, Integer afterScore, Instant afterTime, UUID afterId, int fetch) {
        return jdbc.sql("select " + COLUMNS + JOINS + """
                        where c.post_id = :post and c.parent_id is null and""" + LIVE_AND_VISIBLE + """
                          and (cast(:score as integer) is null
                               or (c.reaction_count, c.created_at, c.id)
                                  < (cast(:score as integer), cast(:t as timestamptz), cast(:id as uuid)))
                        order by c.reaction_count desc, c.created_at desc, c.id desc
                        limit :fetch""")
                .param("me", me)
                .param("post", postId)
                .param("score", afterScore)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query(ROW)
                .list();
    }

    /** Every reply to the given comments, oldest first. */
    public List<Row> repliesOf(UUID me, Collection<UUID> parentIds) {
        if (parentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + JOINS + " where c.parent_id in (:parents) and" + LIVE_AND_VISIBLE
                        + " order by c.created_at, c.id")
                .param("me", me)
                .param("parents", parentIds)
                .query(ROW)
                .list();
    }

    /** Deletes the comment, and its replies with it. Returns how many comments were taken down. */
    public int softDelete(UUID id, Instant now) {
        return jdbc.sql("""
                        update comments set deleted_at = :now
                        where (id = :id or parent_id = :id) and deleted_at is null""")
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The best top-level comment the caller can see, for the post detail page. */
    public Optional<Top> top(UUID me, UUID postId) {
        return jdbc.sql("""
                        select c.id, coalesce(p.name, p.handle::text) as author_name, c.body, c.reaction_count,
                               c.created_at
                        from comments c join profiles p on p.id = c.author_id
                        where c.post_id = :post and c.parent_id is null and""" + LIVE_AND_VISIBLE + """
                        order by c.reaction_count desc, c.created_at, c.id
                        limit 1""")
                .param("me", me)
                .param("post", postId)
                .query((rs, n) -> new Top(rs.getObject("id", UUID.class), rs.getString("author_name"),
                        rs.getString("body"), rs.getInt("reaction_count"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    // --- reactions to comments ----------------------------------------------------------

    public void react(UUID commentId, UUID userId, String kind) {
        jdbc.sql("""
                        insert into comment_reactions (comment_id, user_id, kind) values (:comment, :user, :kind)
                        on conflict (comment_id, user_id) do update set kind = excluded.kind""")
                .param("comment", commentId)
                .param("user", userId)
                .param("kind", kind)
                .update();
    }

    public void clearReaction(UUID commentId, UUID userId) {
        jdbc.sql("delete from comment_reactions where comment_id = :comment and user_id = :user")
                .param("comment", commentId)
                .param("user", userId)
                .update();
    }

    public int score(UUID commentId) {
        return jdbc.sql("select reaction_count from comments where id = :id")
                .param("id", commentId)
                .query(Integer.class)
                .single();
    }
}
