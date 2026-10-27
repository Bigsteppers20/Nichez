package app.nichez.post.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over view totals: one post's week, and an author's totals across their published posts. */
@Repository
public class PostAnalyticsRepository {

    /** Qualifying views in the last 7 days (today included) and in the 7 days before that. */
    public record Weekly(long thisWeek, long lastWeek) {
    }

    /** What an author's published posts add up to. */
    public record Totals(long views, long reactions, long comments, long reshares, long shares, long saves, int posts) {
    }

    /** A post and how many views it has, for the most-viewed-first list. */
    public record ViewedPost(UUID postId, long views) {
    }

    private final JdbcClient jdbc;

    PostAnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Weekly weekly(UUID postId, LocalDate today) {
        return jdbc.sql("""
                        select coalesce(sum(qualifying_views) filter (where day > cast(:today as date) - 7), 0) as this_week,
                               coalesce(sum(qualifying_views) filter (where day <= cast(:today as date) - 7), 0) as last_week
                        from post_daily_views
                        where post_id = :post and day > cast(:today as date) - 14""")
                .param("post", postId)
                .param("today", Date.valueOf(today))
                .query((rs, n) -> new Weekly(rs.getLong("this_week"), rs.getLong("last_week")))
                .single();
    }

    public Weekly weeklyOf(UUID author, LocalDate today) {
        return jdbc.sql("""
                        select coalesce(sum(d.qualifying_views) filter (where d.day > cast(:today as date) - 7), 0) as this_week,
                               coalesce(sum(d.qualifying_views) filter (where d.day <= cast(:today as date) - 7), 0) as last_week
                        from post_daily_views d join posts p on p.id = d.post_id
                        where p.author_id = :author and p.status = 'published' and d.day > cast(:today as date) - 14""")
                .param("author", author)
                .param("today", Date.valueOf(today))
                .query((rs, n) -> new Weekly(rs.getLong("this_week"), rs.getLong("last_week")))
                .single();
    }

    public Totals totals(UUID author) {
        return jdbc.sql("""
                        select coalesce(sum(view_count), 0) as views, coalesce(sum(reaction_count), 0) as reactions,
                               coalesce(sum(comment_count), 0) as comments, coalesce(sum(reshare_count), 0) as reshares,
                               coalesce(sum(share_count), 0) as shares, coalesce(sum(save_count), 0) as saves,
                               count(*)::int as posts
                        from posts where author_id = :author and status = 'published'""")
                .param("author", author)
                .query((rs, n) -> new Totals(rs.getLong("views"), rs.getLong("reactions"), rs.getLong("comments"),
                        rs.getLong("reshares"), rs.getLong("shares"), rs.getLong("saves"), rs.getInt("posts")))
                .single();
    }

    /** The author's published posts, most viewed first. */
    public List<ViewedPost> mostViewed(UUID author, Long afterViews, UUID afterId, int fetch) {
        return jdbc.sql("""
                        select id, view_count from posts
                        where author_id = :author and status = 'published'
                          and (cast(:views as bigint) is null
                               or (view_count, id) < (cast(:views as bigint), cast(:id as uuid)))
                        order by view_count desc, id desc
                        limit :fetch""")
                .param("author", author)
                .param("views", afterViews)
                .param("id", afterId)
                .param("fetch", fetch)
                .query((rs, n) -> new ViewedPost(rs.getObject("id", UUID.class), rs.getLong("view_count")))
                .list();
    }
}
