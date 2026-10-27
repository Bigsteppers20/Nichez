package app.nichez.niche.repository;

import app.nichez.post.repository.PostSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over communities, their members, join requests, invitations and rules. */
@Repository
public class CommunityRepository {

    /** A community as one person sees it: their role in it and whether they have a request waiting. */
    public record Row(UUID id, String name, String description, String emoji, String coverColor, String iconKey,
            UUID homeNicheId, String homeNiche, boolean isPrivate, int members, UUID createdBy, String myRole,
            boolean requested, Instant createdAt, Instant joinedAt) {
    }

    /** A member, or a person waiting to become one (role null). */
    public record Member(UUID userId, String role, Instant at) {
    }

    private static final String SELECT = """
            select c.id, c.name, c.description, c.emoji, c.cover_color, im.final_key as icon_key, c.home_niche_id,
                   n.label as home_niche, c.is_private, c.member_count, c.created_by, cm.role as my_role,
                   exists (select 1 from community_requests rq where rq.community_id = c.id and rq.user_id = :me)
                       as requested,
                   c.created_at, cm.joined_at
            from communities c
            join niches n on n.id = c.home_niche_id
            left join media im on im.id = c.icon_media_id
            left join community_members cm on cm.community_id = c.id and cm.user_id = :me
            """;

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getString("description"), rs.getString("emoji"), rs.getString("cover_color"), rs.getString("icon_key"),
            rs.getObject("home_niche_id", UUID.class), rs.getString("home_niche"), rs.getBoolean("is_private"),
            rs.getInt("member_count"), rs.getObject("created_by", UUID.class), rs.getString("my_role"),
            rs.getBoolean("requested"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("joined_at") == null ? null : rs.getTimestamp("joined_at").toInstant());

    private final JdbcClient jdbc;

    CommunityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID id) {
        return jdbc.sql("select exists (select 1 from communities where id = :id and deleted_at is null)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public Optional<String> roleOf(UUID communityId, UUID userId) {
        return jdbc.sql("select role from community_members where community_id = :c and user_id = :u")
                .param("c", communityId)
                .param("u", userId)
                .query(String.class)
                .optional();
    }

    /** True for the founder and the moderators. */
    public boolean isModerator(UUID communityId, UUID userId) {
        return roleOf(communityId, userId).filter(r -> "founder".equals(r) || "moderator".equals(r)).isPresent();
    }

    public Optional<Row> find(UUID me, UUID id) {
        return jdbc.sql(SELECT + " where c.id = :id and c.deleted_at is null and not exists ("
                        + "select 1 from bans b where b.scope_type = 'community' and b.scope_id = c.id and b.user_id = :me)")
                .param("me", me)
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** Communities the person belongs to, most recently joined first. */
    public List<Row> joined(UUID me, String pattern, Instant afterTime, UUID afterId, int fetch) {
        return jdbc.sql(SELECT + """
                         where c.deleted_at is null and cm.user_id is not null
                           and (cast(:pattern as text) is null or c.name ilike cast(:pattern as text) escape '\\')
                           and (cast(:t as timestamptz) is null
                                or (cm.joined_at, c.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                         order by cm.joined_at desc, c.id desc
                         limit :fetch""")
                .param("me", me)
                .param("pattern", pattern)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query(ROW)
                .list();
    }

    /** Communities the person has not joined, biggest first. Ones they are banned from are left out. */
    public List<Row> discover(UUID me, String pattern, Integer afterMembers, UUID afterId, int fetch) {
        return jdbc.sql(SELECT + """
                         where c.deleted_at is null and cm.user_id is null
                           and not exists (select 1 from bans b where b.scope_type = 'community'
                                           and b.scope_id = c.id and b.user_id = :me)
                           and (cast(:pattern as text) is null or c.name ilike cast(:pattern as text) escape '\\')
                           and (cast(:members as integer) is null
                                or (c.member_count, c.id) < (cast(:members as integer), cast(:id as uuid)))
                         order by c.member_count desc, c.id desc
                         limit :fetch""")
                .param("me", me)
                .param("pattern", pattern)
                .param("members", afterMembers)
                .param("id", afterId)
                .param("fetch", fetch)
                .query(ROW)
                .list();
    }

    /** Communities whose name matches, for search. Offset paging: results are small. */
    public List<Row> search(UUID me, String pattern, int offset, int fetch) {
        return jdbc.sql(SELECT + """
                         where c.deleted_at is null and c.name ilike :pattern escape '\\'
                           and not exists (select 1 from bans b where b.scope_type = 'community'
                                           and b.scope_id = c.id and b.user_id = :me)
                         order by c.member_count desc, c.id desc
                         limit :fetch offset :offset""")
                .param("me", me)
                .param("pattern", pattern)
                .param("fetch", fetch)
                .param("offset", offset)
                .query(ROW)
                .list();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the name is taken (any letter case) */
    public UUID create(UUID founder, String name, String description, String emoji, String coverColor,
            UUID homeNicheId, boolean isPrivate, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into communities (id, name, description, emoji, cover_color, home_niche_id, is_private,
                                                 created_by, created_at)
                        values (:id, :name, :description, :emoji, :color, :niche, :private, :by, :now)""")
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("emoji", emoji)
                .param("color", coverColor)
                .param("niche", homeNicheId)
                .param("private", isPrivate)
                .param("by", founder)
                .param("now", Timestamp.from(now))
                .update();
        addMember(id, founder, "founder", now);
        return id;
    }

    public void addRule(UUID communityId, int position, String body) {
        jdbc.sql("insert into community_rules (community_id, position, body) values (:c, :p, :body)")
                .param("c", communityId)
                .param("p", position)
                .param("body", body)
                .update();
    }

    public List<String> rules(UUID communityId) {
        return jdbc.sql("select body from community_rules where community_id = :c order by position")
                .param("c", communityId)
                .query(String.class)
                .list();
    }

    public void addMember(UUID communityId, UUID userId, String role, Instant now) {
        jdbc.sql("""
                        insert into community_members (community_id, user_id, role, joined_at)
                        values (:c, :u, :role, :now) on conflict do nothing""")
                .param("c", communityId)
                .param("u", userId)
                .param("role", role)
                .param("now", Timestamp.from(now))
                .update();
    }

    public boolean removeMember(UUID communityId, UUID userId) {
        return jdbc.sql("delete from community_members where community_id = :c and user_id = :u")
                .param("c", communityId)
                .param("u", userId)
                .update() > 0;
    }

    public boolean setRole(UUID communityId, UUID userId, String role) {
        return jdbc.sql("update community_members set role = :role where community_id = :c and user_id = :u")
                .param("role", role)
                .param("c", communityId)
                .param("u", userId)
                .update() > 0;
    }

    public void addRequest(UUID communityId, UUID userId, Instant now) {
        jdbc.sql("""
                        insert into community_requests (community_id, user_id, created_at) values (:c, :u, :now)
                        on conflict do nothing""")
                .param("c", communityId)
                .param("u", userId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public boolean removeRequest(UUID communityId, UUID userId) {
        return jdbc.sql("delete from community_requests where community_id = :c and user_id = :u")
                .param("c", communityId)
                .param("u", userId)
                .update() > 0;
    }

    public boolean isInvited(UUID communityId, UUID userId) {
        return jdbc.sql("select exists (select 1 from community_invites where community_id = :c and invitee_id = :u)")
                .param("c", communityId)
                .param("u", userId)
                .query(Boolean.class)
                .single();
    }

    public boolean invite(UUID communityId, UUID inviter, UUID invitee) {
        return jdbc.sql("""
                        insert into community_invites (community_id, inviter_id, invitee_id) values (:c, :by, :to)
                        on conflict do nothing""")
                .param("c", communityId)
                .param("by", inviter)
                .param("to", invitee)
                .update() > 0;
    }

    public boolean isBanned(UUID communityId, UUID userId) {
        return jdbc.sql("select exists (select 1 from bans where scope_type = 'community' and scope_id = :c and user_id = :u)")
                .param("c", communityId)
                .param("u", userId)
                .query(Boolean.class)
                .single();
    }

    /** Members in the order they joined; {@code moderatorsOnly} for the Mods filter. Blocks either way are left out. */
    public List<Member> members(UUID me, UUID communityId, boolean moderatorsOnly, Instant afterTime, UUID afterId,
            int fetch) {
        return jdbc.sql("""
                        select m.user_id, m.role, m.joined_at as at
                        from community_members m
                        where m.community_id = :c and (not :mods or m.role in ('founder', 'moderator'))
                          and %s and %s
                          and (cast(:t as timestamptz) is null
                               or (m.joined_at, m.user_id) > (cast(:t as timestamptz), cast(:id as uuid)))
                        order by m.joined_at, m.user_id
                        limit :fetch""".formatted(PostSql.notBlocked("m.user_id"), PostSql.accountActive("m.user_id")))
                .param("me", me)
                .param("c", communityId)
                .param("mods", moderatorsOnly)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query((rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    /** People waiting to join, oldest request first. */
    public List<Member> requests(UUID me, UUID communityId, Instant afterTime, UUID afterId, int fetch) {
        return jdbc.sql("""
                        select r.user_id, cast(null as text) as role, r.created_at as at
                        from community_requests r
                        where r.community_id = :c and %s and %s
                          and (cast(:t as timestamptz) is null
                               or (r.created_at, r.user_id) > (cast(:t as timestamptz), cast(:id as uuid)))
                        order by r.created_at, r.user_id
                        limit :fetch""".formatted(PostSql.notBlocked("r.user_id"), PostSql.accountActive("r.user_id")))
                .param("me", me)
                .param("c", communityId)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query((rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    /** The first few members, for the row of faces on a community's profile. */
    public List<UUID> memberFaces(UUID me, UUID communityId, int count) {
        return jdbc.sql("""
                        select m.user_id from community_members m
                        where m.community_id = :c and %s and %s
                        order by m.joined_at, m.user_id limit :count""".formatted(
                        PostSql.notBlocked("m.user_id"), PostSql.accountActive("m.user_id")))
                .param("me", me)
                .param("c", communityId)
                .param("count", count)
                .query(UUID.class)
                .list();
    }
}
