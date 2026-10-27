package app.nichez.account.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL for the account lifecycle: data-export requests, and erasing a person who asked to be deleted. */
@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a new request was recorded, false when the person already has one waiting. */
    public boolean requestExport(UUID userId, Instant now) {
        return jdbc.sql("""
                        insert into data_export_requests (user_id, created_at) values (:user, :now)
                        on conflict do nothing""")
                .param("user", userId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    /** People whose deletion was requested at or before {@code cutoff} and who are not erased yet. */
    public List<UUID> dueForErasure(Instant cutoff, int limit) {
        return jdbc.sql("""
                        select id from profiles
                        where deletion_requested_at is not null and deletion_requested_at <= :cutoff
                          and deleted_at is null
                        order by deletion_requested_at, id
                        limit :limit""")
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Erases a person: their posts and comments are taken down (the posts' files follow the usual 30-day
     * retention), what they did to others' content is removed, their relationships and memberships end,
     * and their profile is cleared. Communities they founded pass to the longest-standing moderator or
     * member, or are taken down if nobody else is in them. Run inside one transaction.
     */
    public void erase(UUID userId, Instant now) {
        Timestamp stamp = Timestamp.from(now);
        jdbc.sql("""
                        update posts set status = 'deleted', deleted_at = :now
                        where author_id = :u and status in ('processing', 'published', 'failed')""")
                .param("u", userId).param("now", stamp).update();
        jdbc.sql("update comments set deleted_at = :now where author_id = :u and deleted_at is null")
                .param("u", userId).param("now", stamp).update();
        for (String sql : List.of(
                "delete from reactions where user_id = :u",
                "delete from comment_reactions where user_id = :u",
                "delete from saves where user_id = :u",
                "delete from collections where owner_id = :u",
                "delete from reshares where user_id = :u",
                "delete from post_not_interested where user_id = :u",
                "delete from drafts where owner_id = :u",
                "delete from follows where follower_id = :u or followee_id = :u",
                "delete from blocks where blocker_id = :u or blocked_id = :u",
                "delete from mutes where muter_id = :u or muted_id = :u",
                "delete from user_niches where user_id = :u",
                "delete from community_requests where user_id = :u",
                "delete from search_history where user_id = :u",
                "delete from devices where user_id = :u")) {
            jdbc.sql(sql).param("u", userId).update();
        }
        List<UUID> founded = jdbc.sql("select community_id from community_members where user_id = :u and role = 'founder'")
                .param("u", userId).query(UUID.class).list();
        jdbc.sql("delete from community_members where user_id = :u").param("u", userId).update();
        for (UUID community : founded) {
            int promoted = jdbc.sql("""
                            update community_members set role = 'founder'
                            where (community_id, user_id) = (
                                select community_id, user_id from community_members where community_id = :c
                                order by (role = 'moderator') desc, joined_at, user_id limit 1)""")
                    .param("c", community).update();
            if (promoted == 0) {
                jdbc.sql("update communities set deleted_at = :now where id = :c and deleted_at is null")
                        .param("c", community).param("now", stamp).update();
            }
        }
        jdbc.sql("""
                        update profiles set name = null, handle = null, bio = null, avatar_url = null,
                            avatar_media_id = null, cover_media_id = null, verified = false,
                            deleted_at = :now, updated_at = :now
                        where id = :u""")
                .param("u", userId).param("now", stamp).update();
    }
}
