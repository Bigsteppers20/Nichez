package app.nichez.profile.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL over the profile, handle and niche tables. Not JPA, because {@code handle}
 * is {@code citext} and Hibernate's schema validation rejects it for a String field.
 *
 * <p>Handle comparisons use {@code lower(handle::text)} on purpose. A bare {@code handle = ?}
 * is case-insensitive only when the {@code extensions} schema is on the session's
 * search_path; otherwise Postgres quietly compares as plain text. The unique index does not
 * depend on the search_path, so it remains the final word on duplicates.
 */
@Repository
public class ProfileRepository {

    private final JdbcClient jdbc;

    ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> reservedHandles() {
        return jdbc.sql("select handle::text from reserved_handles").query(String.class).list();
    }

    /** Handles of verified accounts other than {@code exceptUserId}. */
    public List<String> verifiedHandles(UUID exceptUserId) {
        return jdbc.sql("select handle::text from profiles where verified and handle is not null and id <> :me")
                .param("me", exceptUserId)
                .query(String.class)
                .list();
    }

    /** True if someone other than {@code exceptUserId} holds this handle, ignoring case. */
    public boolean isHandleTaken(String handle, UUID exceptUserId) {
        return jdbc.sql("""
                        select exists (select 1 from profiles
                                       where lower(handle::text) = lower(:handle) and id <> :me)""")
                .param("handle", handle)
                .param("me", exceptUserId)
                .query(Boolean.class)
                .single();
    }

    /** True if this user currently holds this handle, ignoring case. */
    public boolean isHandleOwnedBy(String handle, UUID userId) {
        return jdbc.sql("select exists (select 1 from profiles where id = :me and lower(handle::text) = lower(:handle))")
                .param("me", userId)
                .param("handle", handle)
                .query(Boolean.class)
                .single();
    }

    /** The subset of {@code handles} that someone other than {@code exceptUserId} holds, lower-cased. */
    public Set<String> takenAmong(Collection<String> handles, UUID exceptUserId) {
        if (handles.isEmpty()) {
            return Set.of();
        }
        List<String> lowered = handles.stream().map(String::toLowerCase).toList();
        return jdbc.sql("select lower(handle::text) from profiles where lower(handle::text) in (:handles) and id <> :me")
                .param("handles", lowered)
                .param("me", exceptUserId)
                .query(String.class)
                .set()
                .stream()
                .collect(Collectors.toSet());
    }

    /** Slugs of the niches the user joined, in join order. */
    public List<String> nicheSlugsOf(UUID userId) {
        return jdbc.sql("""
                        select n.slug::text from user_niches un join niches n on n.id = un.niche_id
                        where un.user_id = :me order by un.joined_at""")
                .param("me", userId)
                .query(String.class)
                .list();
    }

    /** Slugs of the system niches, for suggestions when the user has joined none. */
    public List<String> systemNicheSlugs() {
        return jdbc.sql("select slug::text from niches where is_system order by sort_order, slug")
                .query(String.class)
                .list();
    }

    /**
     * Gives the user this handle, creating their profile row if it is the first write.
     * A handle held by someone else (any letter case) violates the unique index and surfaces
     * as a {@link org.springframework.dao.DataIntegrityViolationException}.
     */
    public void upsertHandle(UUID userId, String handle, Instant now) {
        jdbc.sql("""
                        insert into profiles (id, handle, created_at, updated_at)
                        values (:id, :handle, :now, :now)
                        on conflict (id) do update set handle = excluded.handle, updated_at = excluded.updated_at""")
                .param("id", userId)
                .param("handle", handle)
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }

    /** Who wrote something, as a post shows them. {@code name} and {@code handle} may be null. */
    public record Summary(UUID id, String name, String handle, String avatarUrl) {
    }

    /** Creates an empty profile row for the user if there is none, so rows that reference it can be written. */
    public void ensureExists(UUID userId, Instant now) {
        jdbc.sql("""
                        insert into profiles (id, created_at, updated_at) values (:id, :now, :now)
                        on conflict (id) do nothing""")
                .param("id", userId)
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }

    /** The subset of {@code userIds} that have a profile. */
    public Set<UUID> existingIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("select id from profiles where id in (:ids)")
                .param("ids", userIds)
                .query(UUID.class)
                .set();
    }

    public java.util.Optional<Summary> summaryOf(UUID userId) {
        return jdbc.sql("select id, name, handle::text as handle, avatar_url from profiles where id = :id")
                .param("id", userId)
                .query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("handle"), rs.getString("avatar_url")))
                .optional();
    }

    /**
     * Stamps onboarded_at once, when the user has a handle and at least {@code minNiches}
     * niches. Returns true if it was set by this call.
     */
    public boolean markOnboardedIfComplete(UUID userId, int minNiches, Instant now) {
        return jdbc.sql("""
                        update profiles set onboarded_at = :now, updated_at = :now
                        where id = :id and onboarded_at is null and handle is not null
                          and (select count(*) from user_niches where user_id = :id) >= :min""")
                .param("id", userId)
                .param("min", minNiches)
                .param("now", java.sql.Timestamp.from(now))
                .update() > 0;
    }
}
