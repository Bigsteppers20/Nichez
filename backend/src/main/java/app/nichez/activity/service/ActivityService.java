package app.nichez.activity.service;

import app.nichez.activity.dto.ActivityItemResponse;
import app.nichez.activity.dto.PushSettingsDtos;
import app.nichez.activity.repository.NotificationRepository;
import app.nichez.activity.repository.NotificationRepository.Row;
import app.nichez.activity.repository.PushPreferencesRepository;
import app.nichez.activity.repository.PushPreferencesRepository.Settings;
import app.nichez.common.ApiException;
import app.nichez.common.CursorPage;
import app.nichez.common.Cursor;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.common.TimeAgo;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Activity tab, the unread badge, push devices and push settings. Who is asking always comes from the token. */
@Service
public class ActivityService {

    public static final String NOTIFICATION_NOT_FOUND = "NOTIFICATION_NOT_FOUND";
    public static final String INVALID_FILTER = "INVALID_FILTER";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<String, List<String>> FILTERS = Map.of(
            "all", List.of(),
            "follows", List.of("follow"),
            "reactions", List.of("reaction"),
            "mentions", List.of("mention"));

    private final NotificationRepository notifications;
    private final PushPreferencesRepository preferences;
    private final UserBriefs briefs;
    private final ProfileRepository profiles;
    private final Clock clock;

    ActivityService(NotificationRepository notifications, PushPreferencesRepository preferences, UserBriefs briefs,
            ProfileRepository profiles, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.briefs = briefs;
        this.profiles = profiles;
        this.clock = clock;
    }

    /** {@code filter} is all, follows, reactions or mentions (default all). */
    @Transactional(readOnly = true)
    public CursorPage<ActivityItemResponse> list(String filter, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        String name = filter == null || filter.isBlank() ? "all" : filter;
        List<String> kinds = FILTERS.get(name);
        if (kinds == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FILTER, "filter must be all, follows, reactions or mentions.");
        }
        int limit = Paging.limit(requestedLimit);
        List<Row> fetched = notifications.list(me, kinds.isEmpty() ? null : kinds, Cursor.TimeId.decode(cursor), limit + 1);
        List<Row> page = Paging.head(fetched, limit);
        Map<UUID, UserBrief> actors = briefs.load(page.stream().map(Row::actorId).filter(java.util.Objects::nonNull).toList());
        var now = clock.instant();
        List<ActivityItemResponse> items = page.stream().map(row -> {
            UserBrief actor = row.actorId() == null ? null : actors.get(row.actorId());
            return new ActivityItemResponse(row.id(), row.actorId(), actor == null ? null : actor.name(),
                    actor == null ? null : actor.handle(), row.message(), TimeAgo.between(row.createdAt(), now),
                    row.createdAt(), row.kind(), actor == null ? null : actor.avatarUrl(), row.unread(),
                    row.postId(), row.commentId());
        }).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, r -> new Cursor.TimeId(r.createdAt(), r.id()).encode()));
    }

    @Transactional(readOnly = true)
    public PushSettingsDtos.UnreadCount unreadCount() {
        return new PushSettingsDtos.UnreadCount(notifications.unreadCount(CurrentUser.id()));
    }

    @Transactional
    public void markAllRead() {
        notifications.markAllRead(CurrentUser.id(), clock.instant());
    }

    /** 404 {@code NOTIFICATION_NOT_FOUND} for an id that is not the caller's, so ids cannot be probed. */
    @Transactional
    public void markRead(UUID id) {
        if (!notifications.markRead(CurrentUser.id(), id, clock.instant())) {
            throw new NotFoundException(NOTIFICATION_NOT_FOUND, "Notification not found.");
        }
    }

    // --- push devices and settings ---------------------------------------------------

    @Transactional
    public void registerDevice(PushSettingsDtos.RegisterDevice body) {
        UUID me = CurrentUser.id();
        // A profile row owns the device row; the push prompt can come before the person has set one up.
        profiles.ensureExists(me, clock.instant());
        preferences.upsertDevice(me, body.token(), body.platform(), clock.instant());
    }

    /** Removes the caller's own token. Anyone else's, or an unknown one, is the same 204: nothing to learn. */
    @Transactional
    public void removeDevice(String token) {
        preferences.deleteDevice(CurrentUser.id(), token);
    }

    @Transactional(readOnly = true)
    public PushSettingsDtos.Settings settings() {
        return toDto(preferences.settings(CurrentUser.id()));
    }

    @Transactional
    public PushSettingsDtos.Settings update(PushSettingsDtos.UpdateSettings body) {
        UUID me = CurrentUser.id();
        // A profile row owns the settings row; make sure the person has one.
        profiles.ensureExists(me, clock.instant());
        Settings current = preferences.settings(me);
        Settings next = new Settings(
                pick(body.pushReactions(), current.pushReactions()),
                pick(body.pushComments(), current.pushComments()),
                pick(body.pushFollows(), current.pushFollows()),
                pick(body.pushMentions(), current.pushMentions()),
                pick(body.pushReshares(), current.pushReshares()),
                pick(body.quietHoursEnabled(), current.quietHoursEnabled()),
                body.quietHoursStart() == null ? current.quietHoursStart() : LocalTime.parse(body.quietHoursStart()),
                body.quietHoursEnd() == null ? current.quietHoursEnd() : LocalTime.parse(body.quietHoursEnd()));
        preferences.save(me, next, clock.instant());
        return toDto(next);
    }

    private static boolean pick(Boolean requested, boolean current) {
        return requested == null ? current : requested;
    }

    private static PushSettingsDtos.Settings toDto(Settings s) {
        return new PushSettingsDtos.Settings(s.pushReactions(), s.pushComments(), s.pushFollows(), s.pushMentions(),
                s.pushReshares(), s.quietHoursEnabled(), s.quietHoursStart().format(HH_MM),
                s.quietHoursEnd().format(HH_MM));
    }
}
