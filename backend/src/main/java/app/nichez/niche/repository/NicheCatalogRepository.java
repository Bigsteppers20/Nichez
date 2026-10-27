package app.nichez.niche.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL over the niche taxonomy and who joined what. Not JPA, because {@code niches.slug} is
 * {@code citext}. The member counts on niches are kept by a trigger on {@code user_niches}.
 */
@Repository
public class NicheCatalogRepository {

    /** A niche, with its taxonomy group when it has one. */
    public record Niche(UUID id, String label, String slug, UUID groupId, String groupName, boolean strict,
            boolean system, int memberCount) {
    }

    private static final String SELECT = """
            select n.id, n.label, n.slug::text as slug, n.group_id, g.name as group_name,
                   coalesce(g.strict, false) as strict, n.is_system, n.member_count
            from niches n left join niche_groups g on g.id = n.group_id""";

    private static final org.springframework.jdbc.core.RowMapper<Niche> NICHE = (rs, n) -> new Niche(
            rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("slug"),
            rs.getObject("group_id", UUID.class), rs.getString("group_name"), rs.getBoolean("strict"),
            rs.getBoolean("is_system"), rs.getInt("member_count"));

    private final JdbcClient jdbc;

    NicheCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The system niches in the order the app shows them: by group, then position in the group. */
    public List<Niche> taxonomy() {
        return jdbc.sql(SELECT + " where n.is_system and g.id is not null order by g.sort_order, n.sort_order, n.label")
                .query(NICHE)
                .list();
    }

    public Optional<Niche> find(UUID id) {
        return jdbc.sql(SELECT + " where n.id = :id").param("id", id).query(NICHE).optional();
    }

    public List<Niche> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where n.id in (:ids)").param("ids", ids).query(NICHE).list();
    }

    /** Niches the user joined, in the order they joined. */
    public List<Niche> joinedBy(UUID userId) {
        return jdbc.sql(SELECT + " join user_niches un on un.niche_id = n.id"
                        + " where un.user_id = :me order by un.joined_at, n.label")
                .param("me", userId)
                .query(NICHE)
                .list();
    }

    public Set<UUID> joinedIds(UUID userId) {
        return jdbc.sql("select niche_id from user_niches where user_id = :me")
                .param("me", userId)
                .query(UUID.class)
                .set();
    }

    public int joinedCount(UUID userId) {
        return jdbc.sql("select count(*)::int from user_niches where user_id = :me")
                .param("me", userId)
                .query(Integer.class)
                .single();
    }

    /** True when the person was added, false when they were already a member. */
    public boolean join(UUID userId, UUID nicheId, Instant now) {
        return jdbc.sql("""
                        insert into user_niches (user_id, niche_id, joined_at) values (:user, :niche, :now)
                        on conflict do nothing""")
                .param("user", userId)
                .param("niche", nicheId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    public boolean leave(UUID userId, UUID nicheId) {
        return jdbc.sql("delete from user_niches where user_id = :user and niche_id = :niche")
                .param("user", userId)
                .param("niche", nicheId)
                .update() > 0;
    }

    public void leaveAll(UUID userId, Collection<UUID> nicheIds) {
        if (nicheIds.isEmpty()) {
            return;
        }
        jdbc.sql("delete from user_niches where user_id = :user and niche_id in (:niches)")
                .param("user", userId)
                .param("niches", nicheIds)
                .update();
    }

    public boolean slugExists(String slug) {
        return jdbc.sql("select exists (select 1 from niches where slug = cast(:slug as extensions.citext))")
                .param("slug", slug)
                .query(Boolean.class)
                .single();
    }

    public Optional<UUID> idBySlug(String slug) {
        return jdbc.sql("select id from niches where lower(slug::text) = lower(:slug)")
                .param("slug", slug)
                .query(UUID.class)
                .optional();
    }

    /** Creates a niche people made themselves (no taxonomy group). */
    public UUID create(String label, String slug, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into niches (id, label, slug, created_by, is_system)
                        values (:id, :label, cast(:slug as extensions.citext), :by, false)""")
                .param("id", id)
                .param("label", label)
                .param("slug", slug)
                .param("by", createdBy)
                .update();
        return id;
    }

    /** True when the person is banned from the niche by a moderator. */
    public boolean isBanned(UUID userId, UUID nicheId) {
        return jdbc.sql("select exists (select 1 from bans where scope_type = 'niche' and scope_id = :niche and user_id = :user)")
                .param("niche", nicheId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }
}
