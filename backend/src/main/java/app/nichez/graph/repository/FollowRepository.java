package app.nichez.graph.repository;

import app.nichez.common.Cursor;
import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code follows}. The counts on profiles are kept by a trigger. */
@Repository
public class FollowRepository {

    /** A person in a followers list and when the follow began. */
    public record Entry(UUID userId, Instant at) {
    }

    /** A suggested person and the niche they share with the caller (null when they share none). */
    public record Suggestion(UUID userId, String sharedNiche) {
    }

    private final JdbcClient jdbc;

    FollowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the follow was created, false when the caller already followed them. */
    public boolean insert(UUID follower, UUID followee, Instant now) {
        return jdbc.sql("""
                        insert into follows (follower_id, followee_id, created_at) values (:follower, :followee, :now)
                        on conflict do nothing""")
                .param("follower", follower)
                .param("followee", followee)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    public boolean delete(UUID follower, UUID followee) {
        return jdbc.sql("delete from follows where follower_id = :follower and followee_id = :followee")
                .param("follower", follower)
                .param("followee", followee)
                .update() > 0;
    }

    /** Removes the follow in both directions (used when someone is blocked). */
    public void deleteBothWays(UUID a, UUID b) {
        jdbc.sql("""
                        delete from follows where (follower_id = :a and followee_id = :b)
                                               or (follower_id = :b and followee_id = :a)""")
                .param("a", a)
                .param("b", b)
                .update();
    }

    public int followerCount(UUID userId) {
        return jdbc.sql("select follower_count from profiles where id = :id")
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** The subset of {@code others} that {@code me} follows. */
    public Set<UUID> followedAmong(UUID me, Collection<UUID> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("select followee_id from follows where follower_id = :me and followee_id in (:others)")
                .param("me", me)
                .param("others", others)
                .query(UUID.class)
                .set();
    }

    /**
     * People related to {@code subject} by {@code filter} (followers, following or mutual), newest
     * relationship first, as the caller may see them: no blocks, no deactivated accounts, and only
     * those matching {@code pattern} (a {@code LIKE} pattern, or null) in name or handle.
     */
    public List<Entry> list(UUID me, UUID subject, String filter, String pattern, Cursor.TimeId after, int fetch) {
        String userColumn;
        String condition;
        switch (filter) {
            case "followers" -> {
                userColumn = "f.follower_id";
                condition = "f.followee_id = :subject";
            }
            case "following" -> {
                userColumn = "f.followee_id";
                condition = "f.follower_id = :subject";
            }
            default -> {
                userColumn = "f.followee_id";
                condition = "f.follower_id = :subject and exists (select 1 from follows b "
                        + "where b.follower_id = f.followee_id and b.followee_id = :subject)";
            }
        }
        return jdbc.sql("""
                        select u.id as user_id, f.created_at as at
                        from follows f join profiles u on u.id = %s
                        where %s and u.handle is not null and %s and %s
                          and (cast(:pattern as text) is null
                               or u.name ilike cast(:pattern as text) escape '\\'
                               or u.handle::text ilike cast(:pattern as text) escape '\\')
                          and (cast(:t as timestamptz) is null
                               or (f.created_at, u.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by f.created_at desc, u.id desc
                        limit :fetch""".formatted(userColumn, condition, PostSql.notBlocked("u.id"),
                        PostSql.accountActive("u.id")))
                .param("me", me)
                .param("subject", subject)
                .param("pattern", pattern)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query((rs, n) -> new Entry(rs.getObject("user_id", UUID.class), rs.getTimestamp("at").toInstant()))
                .list();
    }

    /**
     * Onboarded people the caller does not follow yet, those who share the most niches with the
     * caller first, then the most followed. {@code sharedNiche} is the biggest niche they have in common.
     */
    public List<Suggestion> suggestions(UUID me, int limit) {
        return jdbc.sql("""
                        select p.id as user_id, shared.label as niche
                        from profiles p
                        left join lateral (
                            select n.label, count(*) over () as shared_count
                            from user_niches mine
                            join user_niches theirs on theirs.niche_id = mine.niche_id and theirs.user_id = p.id
                            join niches n on n.id = mine.niche_id
                            where mine.user_id = :me
                            order by n.member_count desc, n.label
                            limit 1) shared on true
                        where p.id <> :me and p.handle is not null and p.onboarded_at is not null
                          and %s and %s
                          and not exists (select 1 from follows f where f.follower_id = :me and f.followee_id = p.id)
                          and not exists (select 1 from mutes mu where mu.muter_id = :me and mu.muted_id = p.id)
                        order by coalesce(shared.shared_count, 0) desc, p.follower_count desc, p.created_at desc, p.id
                        limit :limit""".formatted(PostSql.notBlocked("p.id"), PostSql.accountActive("p.id")))
                .param("me", me)
                .param("limit", limit)
                .query((rs, n) -> new Suggestion(rs.getObject("user_id", UUID.class), rs.getString("niche")))
                .list();
    }
}
