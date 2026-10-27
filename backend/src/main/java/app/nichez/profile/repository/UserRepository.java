package app.nichez.profile.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL over profiles for reading and editing a person. Not JPA, because {@code handle} is
 * {@code citext}. Handle comparisons use {@code lower(handle::text)}, as in {@link ProfileRepository}.
 */
@Repository
public class UserRepository {

    /** A person as a list or a post shows them. {@code avatarKey} is a media object key; {@code avatarUrl} an external URL. */
    public record BriefRow(UUID id, String name, String handle, boolean verified, String avatarKey, String avatarUrl) {
    }

    /** A whole profile row. Keys are object keys, never URLs. */
    public record ProfileRow(
            UUID id, String name, String handle, String bio, boolean verified, String avatarKey, String avatarUrl,
            String coverKey, int followers, int following, int postCount, long totalViews, boolean hasAvatar,
            Instant onboardedAt, Instant deactivatedAt, Instant deletionRequestedAt) {
    }

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BriefRow> briefs(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select p.id, p.name, p.handle::text as handle, p.verified, am.final_key as avatar_key,
                               p.avatar_url
                        from profiles p left join media am on am.id = p.avatar_media_id
                        where p.id in (:ids)""")
                .param("ids", ids)
                .query((rs, n) -> new BriefRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("handle"), rs.getBoolean("verified"), rs.getString("avatar_key"),
                        rs.getString("avatar_url")))
                .list();
    }

    public Optional<ProfileRow> profile(UUID id) {
        return jdbc.sql(profileSql() + " where p.id = :id").param("id", id).query(USER_MAPPER).optional();
    }

    public Optional<ProfileRow> profileByHandle(String handle) {
        return jdbc.sql(profileSql() + " where lower(p.handle::text) = lower(:handle)")
                .param("handle", handle)
                .query(USER_MAPPER)
                .optional();
    }

    private static String profileSql() {
        return """
                select p.id, p.name, p.handle::text as handle, p.bio, p.verified, am.final_key as avatar_key,
                       p.avatar_url, cm.final_key as cover_key, p.follower_count, p.following_count, p.post_count,
                       coalesce((select sum(po.view_count) from posts po
                                 where po.author_id = p.id and po.status = 'published'), 0) as total_views,
                       (p.avatar_media_id is not null or p.avatar_url is not null) as has_avatar,
                       p.onboarded_at, p.deactivated_at, p.deletion_requested_at
                from profiles p
                left join media am on am.id = p.avatar_media_id
                left join media cm on cm.id = p.cover_media_id""";
    }

    private static final org.springframework.jdbc.core.RowMapper<ProfileRow> USER_MAPPER = (rs, n) -> new ProfileRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("handle"), rs.getString("bio"),
            rs.getBoolean("verified"), rs.getString("avatar_key"), rs.getString("avatar_url"),
            rs.getString("cover_key"), rs.getInt("follower_count"), rs.getInt("following_count"),
            rs.getInt("post_count"), rs.getLong("total_views"), rs.getBoolean("has_avatar"),
            instant(rs.getTimestamp("onboarded_at")), instant(rs.getTimestamp("deactivated_at")),
            instant(rs.getTimestamp("deletion_requested_at")));

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** The people holding these handles (any letter case), keyed by the handle in lower case. */
    public java.util.Map<String, UUID> idsByHandles(Collection<String> handles) {
        java.util.Map<String, UUID> result = new java.util.HashMap<>();
        if (handles.isEmpty()) {
            return result;
        }
        jdbc.sql("select lower(handle::text) as handle, id from profiles where lower(handle::text) in (:handles)")
                .param("handles", handles.stream().map(String::toLowerCase).toList())
                .query((rs, n) -> {
                    result.put(rs.getString("handle"), rs.getObject("id", UUID.class));
                    return null;
                })
                .list();
        return result;
    }

    /** Labels of the niches the user joined, in join order (what a profile lists). */
    public List<String> nicheLabels(UUID userId) {
        return jdbc.sql("""
                        select n.label from user_niches un join niches n on n.id = un.niche_id
                        where un.user_id = :me order by un.joined_at, n.label""")
                .param("me", userId)
                .query(String.class)
                .list();
    }

    /** True when the account exists and is neither deactivated nor waiting to be erased nor erased. */
    public boolean isActive(UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from profiles where id = :id and deactivated_at is null
                                       and deletion_requested_at is null and deleted_at is null)""")
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    /** The subset of {@code ids} that are active accounts. */
    public java.util.Set<UUID> activeAmong(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        return jdbc.sql("""
                        select id from profiles where id in (:ids) and deactivated_at is null
                           and deletion_requested_at is null and deleted_at is null""")
                .param("ids", ids)
                .query(UUID.class)
                .set();
    }

    /** Sets name and/or bio; a null argument leaves that column as it is. */
    public void updateText(UUID userId, String name, String bio, Instant now) {
        jdbc.sql("""
                        update profiles set name = coalesce(:name, name), bio = coalesce(:bio, bio), updated_at = :now
                        where id = :id""")
                .param("id", userId)
                .param("name", name)
                .param("bio", bio)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Sets the profile photo. Removing it also clears any external photo URL, so no photo remains. */
    public void setAvatar(UUID userId, UUID mediaIdOrNull, Instant now) {
        jdbc.sql(mediaIdOrNull == null
                        ? "update profiles set avatar_media_id = null, avatar_url = null, updated_at = :now where id = :id"
                        : "update profiles set avatar_media_id = :media, updated_at = :now where id = :id")
                .param("id", userId)
                .param("now", Timestamp.from(now))
                .params(mediaIdOrNull == null ? java.util.Map.of() : java.util.Map.of("media", mediaIdOrNull))
                .update();
    }

    public void setCover(UUID userId, UUID mediaIdOrNull, Instant now) {
        jdbc.sql(mediaIdOrNull == null
                        ? "update profiles set cover_media_id = null, updated_at = :now where id = :id"
                        : "update profiles set cover_media_id = :media, updated_at = :now where id = :id")
                .param("id", userId)
                .param("now", Timestamp.from(now))
                .params(mediaIdOrNull == null ? java.util.Map.of() : java.util.Map.of("media", mediaIdOrNull))
                .update();
    }

    /** Marks the account deactivated, deletion-requested, or active again. */
    public void setAccountState(UUID userId, Instant deactivatedAt, Instant deletionRequestedAt, Instant now) {
        jdbc.sql("""
                        update profiles set deactivated_at = :deactivated, deletion_requested_at = :deletion,
                                            updated_at = :now where id = :id""")
                .param("id", userId)
                .param("deactivated", deactivatedAt == null ? null : Timestamp.from(deactivatedAt))
                .param("deletion", deletionRequestedAt == null ? null : Timestamp.from(deletionRequestedAt))
                .param("now", Timestamp.from(now))
                .update();
    }
}
