package app.nichez.sharing.repository;

import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over shares, reshares, cross-posts and "not interested". Counters are kept by triggers. */
@Repository
public class SharingRepository {

    private final JdbcClient jdbc;

    SharingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShare(UUID postId, UUID userId, String channel, Instant now) {
        jdbc.sql("insert into shares (post_id, user_id, channel, created_at) values (:post, :user, :channel, :now)")
                .param("post", postId)
                .param("user", userId)
                .param("channel", channel)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int shareCount(UUID postId) {
        return jdbc.sql("select share_count from posts where id = :id").param("id", postId).query(Integer.class).single();
    }

    /** True when the reshare was created, false when the caller had already reshared this post. */
    public boolean insertReshare(UUID postId, UUID userId, Instant now) {
        return jdbc.sql("""
                        insert into reshares (post_id, user_id, created_at) values (:post, :user, :now)
                        on conflict do nothing""")
                .param("post", postId)
                .param("user", userId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    public int reshareCount(UUID postId) {
        return jdbc.sql("select reshare_count from posts where id = :id").param("id", postId).query(Integer.class).single();
    }

    /** True when the post was added to the niche, false when it was already there. */
    public boolean insertCrossPost(UUID postId, UUID nicheId, UUID addedBy, Instant now) {
        return jdbc.sql("""
                        insert into post_niches (post_id, niche_id, added_by, created_at)
                        values (:post, :niche, :by, :now) on conflict do nothing""")
                .param("post", postId)
                .param("niche", nicheId)
                .param("by", addedBy)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    public void markNotInterested(UUID userId, UUID postId, Instant now) {
        jdbc.sql("""
                        insert into post_not_interested (user_id, post_id, created_at) values (:user, :post, :now)
                        on conflict do nothing""")
                .param("user", userId)
                .param("post", postId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * People for the "Send to" row: those who follow the caller back first (most recent follows
     * first), then the others the caller follows. Blocked and deactivated people are left out.
     */
    public List<UUID> shareTargets(UUID me, int limit) {
        return jdbc.sql("""
                        select f.followee_id
                        from follows f join profiles u on u.id = f.followee_id
                        where f.follower_id = :me and u.handle is not null and %s and %s
                        order by exists (select 1 from follows b
                                         where b.follower_id = f.followee_id and b.followee_id = :me) desc,
                                 f.created_at desc, f.followee_id
                        limit :limit""".formatted(PostSql.notBlocked("u.id"), PostSql.accountActive("u.id")))
                .param("me", me)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }
}
