package app.nichez.safety.service;

import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.graph.service.FollowService;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.dto.SafetyDtos.ContentPreferencesResponse;
import app.nichez.safety.dto.SafetyDtos.RestrictionItem;
import app.nichez.safety.dto.SafetyDtos.UpdateContentPreferences;
import app.nichez.safety.repository.SafetyRepository;
import app.nichez.safety.repository.SafetyRepository.ContentPreferences;
import app.nichez.safety.repository.SafetyRepository.Entry;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blocking, muting and content filters. A block hides each person from the other everywhere and
 * ends any follow between them; a mute only hides the muted person's posts from the muter. Neither
 * is ever revealed to the person on the other side. Who is asking always comes from the token.
 */
@Service
public class SafetyService {

    public static final String CANNOT_BLOCK_SELF = "CANNOT_BLOCK_SELF";
    public static final String INVALID_RESTRICTION_TYPE = "INVALID_RESTRICTION_TYPE";

    private final SafetyRepository safety;
    private final FollowRepository follows;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final UserBriefs briefs;
    private final Clock clock;

    SafetyService(SafetyRepository safety, FollowRepository follows, UserRepository users, ProfileRepository profiles,
            UserBriefs briefs, Clock clock) {
        this.safety = safety;
        this.follows = follows;
        this.users = users;
        this.profiles = profiles;
        this.briefs = briefs;
        this.clock = clock;
    }

    @Transactional
    public void block(UUID target) {
        UUID me = CurrentUser.id();
        requireOther(me, target);
        safety.block(me, target, clock.instant());
        follows.deleteBothWays(me, target);
    }

    @Transactional
    public void unblock(UUID target) {
        safety.unblock(CurrentUser.id(), target);
    }

    @Transactional
    public void mute(UUID target) {
        UUID me = CurrentUser.id();
        requireOther(me, target);
        safety.mute(me, target, clock.instant());
    }

    @Transactional
    public void unmute(UUID target) {
        safety.unmute(CurrentUser.id(), target);
    }

    /** {@code type} is {@code muted}, {@code blocked} or {@code hiddenNiches} (nothing hides a niche yet, so that list is empty). */
    @Transactional(readOnly = true)
    public CursorPage<RestrictionItem> restrictions(String type, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        if ("hiddenNiches".equals(type)) {
            return new CursorPage<>(List.of(), null);
        }
        if (!"muted".equals(type) && !"blocked".equals(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_RESTRICTION_TYPE,
                    "type must be muted, blocked or hiddenNiches.");
        }
        int limit = Paging.limit(requestedLimit);
        Cursor.TimeId after = Cursor.TimeId.decode(cursor);
        List<Entry> fetched = "muted".equals(type) ? safety.muted(me, after, limit + 1) : safety.blocked(me, after, limit + 1);
        List<Entry> page = Paging.head(fetched, limit);
        Map<UUID, UserBrief> loaded = briefs.load(page.stream().map(Entry::userId).toList());
        List<RestrictionItem> items = page.stream().map(entry -> {
            UserBrief u = briefs.loadOrPlaceholder(entry.userId(), loaded);
            return new RestrictionItem(u.id(), u.name(), u.handle(), u.avatarUrl(), type, entry.at());
        }).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, e -> new Cursor.TimeId(e.at(), e.userId()).encode()));
    }

    @Transactional(readOnly = true)
    public ContentPreferencesResponse preferences() {
        return toDto(safety.preferences(CurrentUser.id()));
    }

    @Transactional
    public ContentPreferencesResponse update(UpdateContentPreferences body) {
        UUID me = CurrentUser.id();
        ContentPreferences current = safety.preferences(me);
        ContentPreferences next = new ContentPreferences(
                body.sensitiveFilter() == null ? current.sensitiveFilter() : body.sensitiveFilter(),
                body.autoHideReported() == null ? current.autoHideReported() : body.autoHideReported());
        // A profile row is the owner of the preferences row; make sure the person has one.
        profiles.ensureExists(me, clock.instant());
        safety.savePreferences(me, next, clock.instant());
        return toDto(next);
    }

    @Transactional
    public ContentPreferencesResponse setAutoHide(boolean enabled) {
        return update(new UpdateContentPreferences(null, enabled));
    }

    private void requireOther(UUID me, UUID target) {
        if (me.equals(target)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CANNOT_BLOCK_SELF, "You cannot do this to yourself.");
        }
        if (users.briefs(List.of(target)).isEmpty()) {
            throw new NotFoundException(FollowService.USER_NOT_FOUND, "User not found.");
        }
    }

    private static ContentPreferencesResponse toDto(ContentPreferences p) {
        return new ContentPreferencesResponse(p.sensitiveFilter(), p.autoHideReported());
    }
}
