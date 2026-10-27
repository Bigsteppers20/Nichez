package app.nichez.search.repository;

import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL behind Explore and search: niches, people, and the person's own search history. */
@Repository
public class SearchRepository {

    /** A niche with how many people joined it and how many joined recently. */
    public record NicheRow(UUID id, String label, String slug, int members, int joinedToday, boolean joined) {
    }

    private final JdbcClient jdbc;

    SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Niches by how many people joined since {@code since}, then by size. */
    public List<NicheRow> trending(UUID me, Instant since, int limit) {
        return jdbc.sql("""
                        select n.id, n.label, n.slug::text as slug, n.member_count,
                               coalesce(j.today, 0)::int as joined_today,
                               exists (select 1 from user_niches u where u.niche_id = n.id and u.user_id = :me) as joined
                        from niches n
                        left join (select niche_id, count(*) as today from user_niches
                                   where joined_at >= :since group by niche_id) j on j.niche_id = n.id
                        order by coalesce(j.today, 0) desc, n.member_count desc, n.label
                        limit :limit""")
                .param("me", me)
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query((rs, i) -> new NicheRow(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getString("slug"), rs.getInt("member_count"), rs.getInt("joined_today"),
                        rs.getBoolean("joined")))
                .list();
    }

    /** Niches whose name matches; names that start with the text come first. */
    public List<NicheRow> niches(UUID me, String contains, String prefix, int offset, int fetch) {
        return jdbc.sql("""
                        select n.id, n.label, n.slug::text as slug, n.member_count, 0 as joined_today,
                               exists (select 1 from user_niches u where u.niche_id = n.id and u.user_id = :me) as joined
                        from niches n
                        where n.label ilike :contains escape '\\' or n.slug::text ilike :contains escape '\\'
                        order by (n.label ilike :prefix escape '\\') desc, n.member_count desc, n.label
                        limit :fetch offset :offset""")
                .param("me", me)
                .param("contains", contains)
                .param("prefix", prefix)
                .param("fetch", fetch)
                .param("offset", offset)
                .query((rs, i) -> new NicheRow(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getString("slug"), rs.getInt("member_count"), rs.getInt("joined_today"),
                        rs.getBoolean("joined")))
                .list();
    }

    /** The busiest niche the person has not joined, for the "nothing matched" screen. */
    public Optional<NicheRow> promoted(UUID me, Instant since) {
        return jdbc.sql("""
                        select n.id, n.label, n.slug::text as slug, n.member_count,
                               (select count(*) from posts p where p.niche_id = n.id and p.status = 'published'
                                  and p.published_at >= :since)::int as joined_today,
                               false as joined
                        from niches n
                        where not exists (select 1 from user_niches u where u.niche_id = n.id and u.user_id = :me)
                        order by joined_today desc, n.member_count desc, n.label
                        limit 1""")
                .param("me", me)
                .param("since", Timestamp.from(since))
                .query((rs, i) -> new NicheRow(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getString("slug"), rs.getInt("member_count"), rs.getInt("joined_today"),
                        rs.getBoolean("joined")))
                .optional();
    }

    /** Published posts in the last day, for the promoted niche's "posts today". */
    public int postsSince(UUID nicheId, Instant since) {
        return jdbc.sql("select count(*)::int from posts where niche_id = :n and status = 'published' and published_at >= :since")
                .param("n", nicheId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** People whose handle or name matches, exact handle first, then handles that start with the text, then the most followed. */
    public List<UUID> people(UUID me, String text, String contains, String prefix, int offset, int fetch) {
        return jdbc.sql("""
                        select p.id from profiles p
                        where p.handle is not null and %s and %s
                          and (p.handle::text ilike :contains escape '\\' or p.name ilike :contains escape '\\')
                        order by (lower(p.handle::text) = lower(:text)) desc,
                                 (p.handle::text ilike :prefix escape '\\') desc,
                                 p.follower_count desc, p.id
                        limit :fetch offset :offset""".formatted(PostSql.notBlocked("p.id"), PostSql.accountActive("p.id")))
                .param("me", me)
                .param("text", text)
                .param("contains", contains)
                .param("prefix", prefix)
                .param("fetch", fetch)
                .param("offset", offset)
                .query(UUID.class)
                .list();
    }

    // --- search history ---------------------------------------------------------------

    public void remember(UUID userId, String query, Instant now) {
        jdbc.sql("""
                        insert into search_history (user_id, query, searched_at) values (:user, :query, :now)
                        on conflict (user_id, query) do update set searched_at = excluded.searched_at""")
                .param("user", userId)
                .param("query", query)
                .param("now", Timestamp.from(now))
                .update();
        // Keep the latest 20.
        jdbc.sql("""
                        delete from search_history where user_id = :user and query in (
                            select query from search_history where user_id = :user
                            order by searched_at desc offset 20)""")
                .param("user", userId)
                .update();
    }

    /** The person's own past searches that start with the text, most recent first. */
    public List<String> history(UUID userId, String prefix, int limit) {
        return jdbc.sql("""
                        select query from search_history
                        where user_id = :user and query like :prefix escape '\\'
                        order by searched_at desc limit :limit""")
                .param("user", userId)
                .param("prefix", prefix)
                .param("limit", limit)
                .query(String.class)
                .list();
    }
}
