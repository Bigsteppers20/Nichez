package app.nichez.graph.service;

import app.nichez.activity.service.Notifier;
import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.common.SqlLike;
import app.nichez.config.SocialProperties;
import app.nichez.graph.dto.GraphDtos.FollowManyResponse;
import app.nichez.graph.dto.GraphDtos.FollowState;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.graph.dto.GraphDtos.SuggestedCreator;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.graph.repository.FollowRepository.Entry;
import app.nichez.graph.repository.FollowRepository.Suggestion;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Following people. Who is asking always comes from the token. Someone who does not exist, is
 * deactivated, or has a block with the caller either way is the same 404 {@code USER_NOT_FOUND}.
 */
@Service
public class FollowService {

    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String CANNOT_FOLLOW_SELF = "CANNOT_FOLLOW_SELF";
    public static final String INVALID_FILTER = "INVALID_FILTER";

    private static final Set<String> FILTERS = Set.of("followers", "following", "mutual");
    private static final int SUGGESTIONS = 20;

    private final FollowRepository follows;
    private final UserRepository users;
    private final UserBriefs briefs;
    private final BlockPolicy blocks;
    private final Notifier notifier;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    FollowService(FollowRepository follows, UserRepository users, UserBriefs briefs, BlockPolicy blocks,
            Notifier notifier, SlidingWindowRateLimiter limiter, SocialProperties social, Clock clock) {
        this.follows = follows;
        this.users = users;
        this.briefs = briefs;
        this.blocks = blocks;
        this.notifier = notifier;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    @Transactional
    public FollowState follow(UUID targetId) {
        UUID me = CurrentUser.id();
        limiter.check("follow:" + me, social.followsPerHour(), Duration.ofHours(1));
        if (me.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CANNOT_FOLLOW_SELF, "You cannot follow yourself.");
        }
        requireFollowable(me, targetId);
        if (follows.insert(me, targetId, clock.instant())) {
            notifier.followed(me, targetId);
        }
        return new FollowState(true, follows.followerCount(targetId));
    }

    /** Unfollowing someone you do not follow is not an error. */
    @Transactional
    public FollowState unfollow(UUID targetId) {
        UUID me = CurrentUser.id();
        follows.delete(me, targetId);
        return new FollowState(false, follows.followerCount(targetId));
    }

    /** Follows each person who can be followed; the rest are skipped. */
    @Transactional
    public FollowManyResponse followMany(List<UUID> userIds) {
        UUID me = CurrentUser.id();
        Set<UUID> targets = new java.util.LinkedHashSet<>(userIds);
        targets.remove(me);
        for (int i = 0; i < targets.size(); i++) {
            limiter.check("follow:" + me, social.followsPerHour(), Duration.ofHours(1));
        }
        Set<UUID> existing = users.activeAmong(targets);
        Set<UUID> blocked = blocks.blockedEitherWay(me, targets);
        int followed = 0;
        for (UUID target : targets) {
            if (existing.contains(target) && !blocked.contains(target) && follows.insert(me, target, clock.instant())) {
                notifier.followed(me, target);
                followed++;
            }
        }
        return new FollowManyResponse(followed);
    }

    /** {@code filter} is followers (default), following or mutual; {@code q} matches name or handle. */
    @Transactional(readOnly = true)
    public CursorPage<PersonItem> people(UUID subject, String filter, String q, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        String chosen = filter == null || filter.isBlank() ? "followers" : filter;
        if (!FILTERS.contains(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FILTER, "filter must be followers, following or mutual.");
        }
        requireVisible(me, subject);
        int limit = Paging.limit(requestedLimit);
        List<Entry> fetched = follows.list(me, subject, chosen, SqlLike.contains(q), Cursor.TimeId.decode(cursor), limit + 1);
        List<Entry> page = Paging.head(fetched, limit);
        List<UUID> ids = page.stream().map(Entry::userId).toList();
        Map<UUID, UserBrief> loaded = briefs.load(ids);
        Set<UUID> followed = follows.followedAmong(me, ids);
        List<PersonItem> items = page.stream().map(entry -> {
            UserBrief u = briefs.loadOrPlaceholder(entry.userId(), loaded);
            return new PersonItem(u.id(), u.name(), u.handle(), u.avatarUrl(), u.verified(),
                    followed.contains(u.id()), u.id().equals(me));
        }).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, e -> new Cursor.TimeId(e.at(), e.userId()).encode()));
    }

    /** People worth following for the caller's niches (onboarding step A6). */
    @Transactional(readOnly = true)
    public CursorPage<SuggestedCreator> suggested() {
        UUID me = CurrentUser.id();
        List<Suggestion> found = follows.suggestions(me, SUGGESTIONS);
        Map<UUID, UserBrief> loaded = briefs.load(found.stream().map(Suggestion::userId).toList());
        List<SuggestedCreator> items = found.stream().map(s -> {
            UserBrief u = briefs.loadOrPlaceholder(s.userId(), loaded);
            return new SuggestedCreator(u.id(), u.name(), u.handle(), s.sharedNiche(), u.avatarUrl(), false);
        }).toList();
        return new CursorPage<>(items, null);
    }

    private void requireFollowable(UUID me, UUID target) {
        requireVisible(me, target);
    }

    /** The person exists, is active, and has no block with the caller. */
    public void requireVisible(UUID me, UUID target) {
        if (!users.isActive(target) || (!me.equals(target) && blocks.isBlockedEitherWay(me, target))) {
            throw new NotFoundException(USER_NOT_FOUND, "User not found.");
        }
    }

    /** For callers that only have the ids. */
    public Set<UUID> activeAmong(Collection<UUID> ids) {
        return users.activeAmong(ids);
    }
}
