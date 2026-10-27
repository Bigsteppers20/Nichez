package app.nichez.safety.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over reports, the bans they lead to, and the record of what moderators decided. */
@Repository
public class ReportRepository {

    /** One report as stored. */
    public record Basic(UUID id, UUID reporterId, String targetType, UUID targetId, UUID nicheId, UUID communityId,
            String status) {
    }

    /** All the reports on one target, folded into one line of the moderation queue. */
    public record Group(UUID id, String targetType, UUID targetId, String reasonId, int count, boolean severe,
            String verdict, Instant at) {
    }

    /** Who made, and a short preview of, something that was reported. */
    public record Preview(UUID targetId, String authorHandle, String excerpt, String mediaKey) {
    }

    /** The niche and community a post belongs to. */
    public record PostScope(UUID authorId, UUID nicheId, UUID communityId) {
    }

    private static final String GROUPED = """
            select (array_agg(r.id order by r.created_at, r.id))[1] as id, r.target_type, r.target_id,
                   (array_agg(r.reason_id order by r.created_at, r.id))[1] as reason_id,
                   count(*)::int as n, bool_or(r.severe) as severe,
                   (array_agg(r.verdict order by r.resolved_at desc nulls last))[1] as verdict,
                   %s as at
            from reports r
            where r.status = :status and %s
            group by r.target_type, r.target_id""";

    private final JdbcClient jdbc;

    ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Files a report, or returns the id of the one this person already filed on the same target
     * (a person reports a target once).
     */
    public UUID insert(UUID reporter, String targetType, UUID targetId, UUID nicheId, UUID communityId, String reasonId,
            String note, boolean severe, Instant now) {
        Optional<UUID> created = jdbc.sql("""
                        insert into reports (reporter_id, target_type, target_id, niche_id, community_id, reason_id,
                                             note, severe, created_at)
                        values (:reporter, :type, :target, :niche, :community, :reason, :note, :severe, :now)
                        on conflict (reporter_id, target_type, target_id) do nothing
                        returning id""")
                .param("reporter", reporter)
                .param("type", targetType)
                .param("target", targetId)
                .param("niche", nicheId)
                .param("community", communityId)
                .param("reason", reasonId)
                .param("note", note)
                .param("severe", severe)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .optional();
        if (created.isPresent()) {
            return created.get();
        }
        return jdbc.sql("select id from reports where reporter_id = :reporter and target_type = :type and target_id = :target")
                .param("reporter", reporter)
                .param("type", targetType)
                .param("target", targetId)
                .query(UUID.class)
                .single();
    }

    public Optional<Basic> find(UUID id) {
        return jdbc.sql("""
                        select id, reporter_id, target_type, target_id, niche_id, community_id, status
                        from reports where id = :id""")
                .param("id", id)
                .query((rs, n) -> new Basic(rs.getObject("id", UUID.class), rs.getObject("reporter_id", UUID.class),
                        rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                        rs.getObject("niche_id", UUID.class), rs.getObject("community_id", UUID.class),
                        rs.getString("status")))
                .optional();
    }

    /** Open reports, oldest first, for a niche (not counting community reports) or for a community. */
    public List<Group> open(String scope, UUID scopeId, Instant afterTime, UUID afterId, int fetch) {
        String where = "niche".equals(scope) ? "r.niche_id = :scope and r.community_id is null" : "r.community_id = :scope";
        String first = "(array_agg(r.id order by r.created_at, r.id))[1]";
        return jdbc.sql(GROUPED.formatted("min(r.created_at)", where) + """

                        having (cast(:t as timestamptz) is null
                                or (min(r.created_at), %1$s) > (cast(:t as timestamptz), cast(:id as uuid)))
                        order by min(r.created_at), %1$s
                        limit :fetch""".formatted(first))
                .param("status", "open")
                .param("scope", scopeId)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query(groupMapper())
                .list();
    }

    /** Decided reports, newest decision first. */
    public List<Group> resolved(String scope, UUID scopeId, Instant afterTime, UUID afterId, int fetch) {
        String where = "niche".equals(scope) ? "r.niche_id = :scope and r.community_id is null" : "r.community_id = :scope";
        String first = "(array_agg(r.id order by r.created_at, r.id))[1]";
        return jdbc.sql(GROUPED.formatted("max(r.resolved_at)", where) + """

                        having (cast(:t as timestamptz) is null
                                or (max(r.resolved_at), %1$s) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by max(r.resolved_at) desc, %1$s desc
                        limit :fetch""".formatted(first))
                .param("status", "resolved")
                .param("scope", scopeId)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query(groupMapper())
                .list();
    }

    private static org.springframework.jdbc.core.RowMapper<Group> groupMapper() {
        return (rs, n) -> new Group(rs.getObject("id", UUID.class), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), rs.getString("reason_id"), rs.getInt("n"),
                rs.getBoolean("severe"), rs.getString("verdict"), rs.getTimestamp("at").toInstant());
    }

    /** Marks every open report on the target as decided. Returns how many there were. */
    public int resolveAll(String targetType, UUID targetId, String verdict, UUID actor, Instant now) {
        return jdbc.sql("""
                        update reports set status = 'resolved', verdict = :verdict, resolved_by = :actor, resolved_at = :now
                        where target_type = :type and target_id = :target and status = 'open'""")
                .param("verdict", verdict)
                .param("actor", actor)
                .param("now", Timestamp.from(now))
                .param("type", targetType)
                .param("target", targetId)
                .update();
    }

    public void recordAction(UUID reportId, UUID actor, String verdict, String targetType, UUID targetId, UUID nicheId,
            UUID communityId, Instant now) {
        jdbc.sql("""
                        insert into moderation_actions (report_id, actor_id, verdict, target_type, target_id, niche_id,
                                                        community_id, created_at)
                        values (:report, :actor, :verdict, :type, :target, :niche, :community, :now)""")
                .param("report", reportId)
                .param("actor", actor)
                .param("verdict", verdict)
                .param("type", targetType)
                .param("target", targetId)
                .param("niche", nicheId)
                .param("community", communityId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void ban(String scopeType, UUID scopeId, UUID userId, UUID bannedBy, Instant now) {
        jdbc.sql("""
                        insert into bans (scope_type, scope_id, user_id, banned_by, created_at)
                        values (:type, :scope, :user, :by, :now) on conflict do nothing""")
                .param("type", scopeType)
                .param("scope", scopeId)
                .param("user", userId)
                .param("by", bannedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Takes a banned person out of a community. The founder cannot be removed this way. */
    public void removeCommunityMember(UUID communityId, UUID userId) {
        jdbc.sql("delete from community_members where community_id = :community and user_id = :user and role <> 'founder'")
                .param("community", communityId)
                .param("user", userId)
                .update();
        jdbc.sql("delete from community_requests where community_id = :community and user_id = :user")
                .param("community", communityId)
                .param("user", userId)
                .update();
    }

    public void removeNicheMember(UUID nicheId, UUID userId) {
        jdbc.sql("delete from user_niches where niche_id = :niche and user_id = :user")
                .param("niche", nicheId)
                .param("user", userId)
                .update();
    }

    public void takeDownCommunity(UUID communityId, Instant now) {
        jdbc.sql("update communities set deleted_at = :now where id = :id and deleted_at is null")
                .param("id", communityId)
                .param("now", Timestamp.from(now))
                .update();
    }

    // --- what was reported ---------------------------------------------------------------

    public Optional<PostScope> postScope(UUID postId) {
        return jdbc.sql("select author_id, niche_id, community_id from posts where id = :id")
                .param("id", postId)
                .query((rs, n) -> new PostScope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)))
                .optional();
    }

    public boolean communityVisible(UUID me, UUID communityId) {
        return jdbc.sql("""
                        select exists (select 1 from communities c where c.id = :id and c.deleted_at is null
                            and (not c.is_private or exists (select 1 from community_members m
                                                              where m.community_id = c.id and m.user_id = :me)))""")
                .param("id", communityId)
                .param("me", me)
                .query(Boolean.class)
                .single();
    }

    public Optional<UUID> communityOwner(UUID communityId) {
        return jdbc.sql("select created_by from communities where id = :id")
                .param("id", communityId)
                .query(UUID.class)
                .optional();
    }

    public Optional<Preview> postPreview(UUID id) {
        return jdbc.sql("""
                        select p.id, a.handle::text as handle, left(coalesce(p.caption, ''), 140) as excerpt,
                               coalesce(m.poster_key, m.final_key, m.original_key) as media_key
                        from posts p join profiles a on a.id = p.author_id join media m on m.id = p.media_id
                        where p.id = :id""")
                .param("id", id)
                .query(previewMapper())
                .optional();
    }

    public Optional<Preview> commentPreview(UUID id) {
        return jdbc.sql("""
                        select c.id, a.handle::text as handle, left(c.body, 140) as excerpt, cast(null as text) as media_key
                        from comments c join profiles a on a.id = c.author_id where c.id = :id""")
                .param("id", id)
                .query(previewMapper())
                .optional();
    }

    public Optional<Preview> userPreview(UUID id) {
        return jdbc.sql("""
                        select p.id, p.handle::text as handle, left(coalesce(p.bio, ''), 140) as excerpt,
                               cast(null as text) as media_key
                        from profiles p where p.id = :id""")
                .param("id", id)
                .query(previewMapper())
                .optional();
    }

    public Optional<Preview> communityPreview(UUID id) {
        return jdbc.sql("""
                        select c.id, a.handle::text as handle,
                               left(c.name || coalesce(': ' || c.description, ''), 140) as excerpt,
                               cast(null as text) as media_key
                        from communities c join profiles a on a.id = c.created_by where c.id = :id""")
                .param("id", id)
                .query(previewMapper())
                .optional();
    }

    private static org.springframework.jdbc.core.RowMapper<Preview> previewMapper() {
        return (rs, n) -> new Preview(rs.getObject("id", UUID.class), rs.getString("handle"), rs.getString("excerpt"),
                rs.getString("media_key"));
    }
}
