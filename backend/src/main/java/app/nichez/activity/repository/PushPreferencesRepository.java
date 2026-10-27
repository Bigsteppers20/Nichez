package app.nichez.activity.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code notification_settings} and {@code devices}: which pushes a person gets, and where they go. */
@Repository
public class PushPreferencesRepository {

    /** One person's push choices. A person with no row has the defaults below. */
    public record Settings(boolean pushReactions, boolean pushComments, boolean pushFollows, boolean pushMentions,
            boolean pushReshares, boolean quietHoursEnabled, LocalTime quietHoursStart, LocalTime quietHoursEnd) {

        public static Settings defaults() {
            return new Settings(true, true, true, true, true, false, LocalTime.of(22, 0), LocalTime.of(7, 0));
        }

        public boolean allows(String kind) {
            return switch (kind) {
                case "reaction" -> pushReactions;
                case "comment" -> pushComments;
                case "follow" -> pushFollows;
                case "mention" -> pushMentions;
                case "reshare" -> pushReshares;
                default -> true;
            };
        }
    }

    private final JdbcClient jdbc;

    PushPreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Settings settings(UUID userId) {
        return jdbc.sql("""
                        select push_reactions, push_comments, push_follows, push_mentions, push_reshares,
                               quiet_hours_enabled, quiet_hours_start, quiet_hours_end
                        from notification_settings where user_id = :id""")
                .param("id", userId)
                .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getTime(7).toLocalTime(),
                        rs.getTime(8).toLocalTime()))
                .optional()
                .orElseGet(Settings::defaults);
    }

    public void save(UUID userId, Settings settings, Instant now) {
        jdbc.sql("""
                        insert into notification_settings (user_id, push_reactions, push_comments, push_follows,
                            push_mentions, push_reshares, quiet_hours_enabled, quiet_hours_start, quiet_hours_end,
                            updated_at)
                        values (:id, :r, :c, :f, :m, :s, :qe, :qs, :qend, :now)
                        on conflict (user_id) do update set push_reactions = excluded.push_reactions,
                            push_comments = excluded.push_comments, push_follows = excluded.push_follows,
                            push_mentions = excluded.push_mentions, push_reshares = excluded.push_reshares,
                            quiet_hours_enabled = excluded.quiet_hours_enabled,
                            quiet_hours_start = excluded.quiet_hours_start,
                            quiet_hours_end = excluded.quiet_hours_end""")
                .param("id", userId)
                .param("r", settings.pushReactions())
                .param("c", settings.pushComments())
                .param("f", settings.pushFollows())
                .param("m", settings.pushMentions())
                .param("s", settings.pushReshares())
                .param("qe", settings.quietHoursEnabled())
                .param("qs", java.sql.Time.valueOf(settings.quietHoursStart()))
                .param("qend", java.sql.Time.valueOf(settings.quietHoursEnd()))
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Registers a push token for the caller. A token belongs to one person at a time: signing in as
     * someone else on the same phone moves it.
     */
    public void upsertDevice(UUID userId, String token, String platform, Instant now) {
        jdbc.sql("""
                        insert into devices (user_id, token, platform, created_at, last_seen_at)
                        values (:user, :token, :platform, :now, :now)
                        on conflict (token) do update set user_id = excluded.user_id, platform = excluded.platform,
                            last_seen_at = excluded.last_seen_at""")
                .param("user", userId)
                .param("token", token)
                .param("platform", platform)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Removes the caller's own token only. */
    public boolean deleteDevice(UUID userId, String token) {
        return jdbc.sql("delete from devices where user_id = :user and token = :token")
                .param("user", userId)
                .param("token", token)
                .update() > 0;
    }

    public List<String> tokensOf(UUID userId) {
        return jdbc.sql("select token from devices where user_id = :id order by last_seen_at desc")
                .param("id", userId)
                .query(String.class)
                .list();
    }

    public Optional<String> platformOf(String token) {
        return jdbc.sql("select platform from devices where token = :token")
                .param("token", token)
                .query(String.class)
                .optional();
    }
}
