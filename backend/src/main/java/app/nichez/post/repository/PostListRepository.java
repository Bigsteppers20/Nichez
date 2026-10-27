package app.nichez.post.repository;

import app.nichez.common.Cursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ids of the posts that belong in a list, newest first, for one viewer. Every query applies
 * {@link PostSql#VISIBLE}. The cards themselves are loaded afterwards in one batch by
 * {@code PostCards}.
 */
@Repository
public class PostListRepository {

    /** A post in a list: when it entered this list, and who reshared it there (feed only). */
    public record Item(UUID postId, Instant at, UUID resharedBy) {
    }

    private static final RowMapper<Item> ITEM = (rs, n) -> new Item(rs.getObject("post_id", UUID.class),
            rs.getTimestamp("at").toInstant(), rs.getObject("by_user", UUID.class));

    private final JdbcClient jdbc;

    PostListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The home feed. With no {@code nicheId}: posts from the viewer's niches and the people they
     * follow, their own, and what the people they follow reshared. With one: that niche (posts
     * made there and posts cross-posted into it).
     */
    public List<Item> feed(UUID me, UUID nicheId, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select post_id, at, by_user from (
                            select distinct on (post_id) post_id, at, by_user from (
                                select p.id as post_id, p.published_at as at, cast(null as uuid) as by_user
                                from posts p
                                where %1$s %2$s
                                  and (case when cast(:niche as uuid) is null then
                                        (p.author_id = :me
                                         or p.niche_id in (select un.niche_id from user_niches un where un.user_id = :me)
                                         or p.author_id in (select f.followee_id from follows f where f.follower_id = :me)
                                         or exists (select 1 from post_niches pn
                                                    join user_niches un on un.niche_id = pn.niche_id and un.user_id = :me
                                                    where pn.post_id = p.id))
                                       else
                                        (p.niche_id = :niche
                                         or exists (select 1 from post_niches pn
                                                    where pn.post_id = p.id and pn.niche_id = :niche))
                                       end)
                                union all
                                select p.id, rs.created_at, rs.user_id
                                from reshares rs join posts p on p.id = rs.post_id
                                where cast(:niche as uuid) is null
                                  and rs.user_id <> :me
                                  and rs.user_id in (select f.followee_id from follows f where f.follower_id = :me)
                                  and %1$s %2$s
                                  and %3$s
                            ) candidates
                            order by post_id, at desc
                        ) latest
                        where (cast(:t as timestamptz) is null
                               or (at, post_id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by at desc, post_id desc
                        limit :fetch""".formatted(PostSql.VISIBLE, PostSql.FEED_EXTRAS, PostSql.notBlocked("rs.user_id")))
                .param("me", me)
                .param("niche", nicheId)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** A person's published posts; {@code videoOnly} for the Media tab. */
    public List<Item> byAuthor(UUID me, UUID author, boolean videoOnly, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, p.published_at as at, cast(null as uuid) as by_user
                        from posts p join media m on m.id = p.media_id
                        where p.author_id = :author and %s
                          and (not :videoOnly or m.kind = 'video')
                          and (cast(:t as timestamptz) is null
                               or (p.published_at, p.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by p.published_at desc, p.id desc
                        limit :fetch""".formatted(PostSql.VISIBLE))
                .param("me", me)
                .param("author", author)
                .param("videoOnly", videoOnly)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** Posts made in a community. The caller must be allowed to see the community (checked by the caller). */
    public List<Item> inCommunity(UUID me, UUID communityId, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, p.published_at as at, cast(null as uuid) as by_user
                        from posts p
                        where p.community_id = :community and %s
                          and (cast(:t as timestamptz) is null
                               or (p.published_at, p.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by p.published_at desc, p.id desc
                        limit :fetch""".formatted(PostSql.VISIBLE))
                .param("me", me)
                .param("community", communityId)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** The posts a person saved, newest save first. Only posts they can still see. */
    public List<Item> saved(UUID me, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, s.created_at as at, cast(null as uuid) as by_user
                        from saves s join posts p on p.id = s.post_id
                        where s.user_id = :me and %s
                          and (cast(:t as timestamptz) is null
                               or (s.created_at, p.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by s.created_at desc, p.id desc
                        limit :fetch""".formatted(PostSql.VISIBLE))
                .param("me", me)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** The posts in one of the caller's collections, newest addition first. */
    public List<Item> inCollection(UUID me, UUID collectionId, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, cp.added_at as at, cast(null as uuid) as by_user
                        from collection_posts cp join posts p on p.id = cp.post_id
                        where cp.collection_id = :collection and %s
                          and (cast(:t as timestamptz) is null
                               or (cp.added_at, p.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by cp.added_at desc, p.id desc
                        limit :fetch""".formatted(PostSql.VISIBLE))
                .param("me", me)
                .param("collection", collectionId)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** Recent published posts across niches the viewer can see: the Explore grid. */
    public List<Item> recent(UUID me, Cursor.TimeId after, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, p.published_at as at, cast(null as uuid) as by_user
                        from posts p
                        where %s %s
                          and (cast(:t as timestamptz) is null
                               or (p.published_at, p.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by p.published_at desc, p.id desc
                        limit :fetch""".formatted(PostSql.VISIBLE, PostSql.FEED_EXTRAS))
                .param("me", me)
                .param("t", after == null ? null : Timestamp.from(after.time()))
                .param("id", after == null ? null : after.id())
                .param("fetch", fetch)
                .query(ITEM)
                .list();
    }

    /** Posts whose caption matches {@code pattern}, newest first (offset paging: search results are small). */
    public List<Item> search(UUID me, String pattern, boolean videoOnly, int offset, int fetch) {
        return jdbc.sql("""
                        select p.id as post_id, p.published_at as at, cast(null as uuid) as by_user
                        from posts p join media m on m.id = p.media_id
                        where %s %s
                          and p.caption ilike :pattern escape '\\'
                          and (not :videoOnly or m.kind = 'video')
                        order by p.published_at desc, p.id desc
                        limit :fetch offset :offset""".formatted(PostSql.VISIBLE, PostSql.FEED_EXTRAS))
                .param("me", me)
                .param("pattern", pattern)
                .param("videoOnly", videoOnly)
                .param("fetch", fetch)
                .param("offset", offset)
                .query(ITEM)
                .list();
    }
}
