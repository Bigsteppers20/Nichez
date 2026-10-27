package app.nichez.niche.service;

import app.nichez.common.ApiException;
import app.nichez.common.ConflictException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.ErrorCode;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.common.SqlLike;
import app.nichez.config.AppProperties;
import app.nichez.config.SocialProperties;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.niche.dto.CommunityDtos.CommunityResponse;
import app.nichez.niche.dto.CommunityDtos.CreateCommunityRequest;
import app.nichez.niche.dto.CommunityDtos.InviteResult;
import app.nichez.niche.dto.CommunityDtos.JoinState;
import app.nichez.niche.dto.CommunityDtos.MemberItem;
import app.nichez.niche.dto.CommunityDtos.RulesResponse;
import app.nichez.niche.repository.CommunityRepository;
import app.nichez.niche.repository.CommunityRepository.Member;
import app.nichez.niche.repository.CommunityRepository.Row;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.post.service.PostPages;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Communities: groups inside a home niche. A public one is joined at once; a private one takes a
 * request that a moderator approves (an invitation skips the wait). The founder and moderators run
 * it. Who is asking always comes from the token, and authority is checked here. A community the
 * caller is banned from is the same 404 as one that does not exist.
 */
@Service
public class CommunityService {

    public static final String COMMUNITY_NOT_FOUND = "COMMUNITY_NOT_FOUND";
    public static final String COMMUNITY_NAME_TAKEN = "COMMUNITY_NAME_TAKEN";
    public static final String BANNED_FROM_COMMUNITY = "BANNED_FROM_COMMUNITY";
    public static final String NOT_A_COMMUNITY_MEMBER = "NOT_A_COMMUNITY_MEMBER";
    public static final String FOUNDER_CANNOT_LEAVE = "FOUNDER_CANNOT_LEAVE";
    public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    public static final String REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND";
    public static final String INVALID_FILTER = "INVALID_FILTER";
    public static final String INVALID_TAB = "INVALID_TAB";

    private static final int FACES = 3;
    private static final String DEFAULT_COLOR = "#E8EEF4";
    private static final String DEFAULT_EMOJI = "💬";

    private final CommunityRepository communities;
    private final NicheRepository niches;
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final UserBriefs briefs;
    private final BlockPolicy blocks;
    private final PostListRepository lists;
    private final PostPages pages;
    private final MediaUrlProvider urls;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final AppProperties app;
    private final Clock clock;

    CommunityService(CommunityRepository communities, NicheRepository niches, ProfileRepository profiles,
            UserRepository users, UserBriefs briefs, BlockPolicy blocks, PostListRepository lists, PostPages pages,
            MediaUrlProvider urls, SlidingWindowRateLimiter limiter, SocialProperties social, AppProperties app,
            Clock clock) {
        this.communities = communities;
        this.niches = niches;
        this.profiles = profiles;
        this.users = users;
        this.briefs = briefs;
        this.blocks = blocks;
        this.lists = lists;
        this.pages = pages;
        this.urls = urls;
        this.limiter = limiter;
        this.social = social;
        this.app = app;
        this.clock = clock;
    }

    /** {@code tab}: {@code discover} (default) or {@code joined}; {@code q} filters by name. */
    @Transactional(readOnly = true)
    public CursorPage<CommunityResponse> list(String tab, String q, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        String chosen = tab == null || tab.isBlank() ? "discover" : tab;
        if (!"discover".equals(chosen) && !"joined".equals(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_TAB, "tab must be discover or joined.");
        }
        int limit = Paging.limit(requestedLimit);
        String pattern = SqlLike.contains(q);
        List<Row> fetched;
        java.util.function.Function<Row, String> cursorOf;
        if ("joined".equals(chosen)) {
            Cursor.TimeId after = Cursor.TimeId.decode(cursor);
            fetched = communities.joined(me, pattern, after == null ? null : after.time(),
                    after == null ? null : after.id(), limit + 1);
            // Ordered by when the caller joined, so the cursor carries that time.
            cursorOf = row -> new Cursor.TimeId(row.joinedAt(), row.id()).encode();
        } else {
            List<String> parts = Cursor.decode(cursor, 2);
            fetched = communities.discover(me, pattern, parts == null ? null : (int) Cursor.number(parts.get(0)),
                    parts == null ? null : Cursor.uuid(parts.get(1)), limit + 1);
            cursorOf = row -> Cursor.encode(row.members(), row.id());
        }
        List<CommunityResponse> items = Paging.head(fetched, limit).stream().map(row -> toResponse(row, null)).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, cursorOf));
    }

    /** The profile. Visible to anyone not banned, so a private community can be seen before asking to join. */
    @Transactional(readOnly = true)
    public CommunityResponse get(UUID id) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        List<UUID> faceIds = communities.memberFaces(me, id, FACES);
        Map<UUID, UserBrief> loaded = briefs.load(faceIds);
        List<UserBrief> faces = faceIds.stream().map(f -> briefs.loadOrPlaceholder(f, loaded)).toList();
        return toResponse(row, faces);
    }

    @Transactional(readOnly = true)
    public RulesResponse rules(UUID id) {
        communities.find(CurrentUser.id(), id).orElseThrow(CommunityService::notFound);
        return new RulesResponse(communities.rules(id));
    }

    /** A private community's posts are for its members only (403 {@code NOT_A_COMMUNITY_MEMBER}). */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> posts(UUID id, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if (row.isPrivate() && row.myRole() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_COMMUNITY_MEMBER, "Join this community to see its posts.");
        }
        int limit = Paging.limit(requestedLimit);
        List<Item> fetched = lists.inCommunity(me, id, Cursor.TimeId.decode(cursor), limit + 1);
        return pages.page(me, fetched, limit);
    }

    /** Creates a community; the caller becomes its founder. */
    @Transactional
    public CommunityResponse create(CreateCommunityRequest request) {
        UUID me = CurrentUser.id();
        limiter.check("community-create:" + me, social.communitiesPerDay(), Duration.ofDays(1));
        if (!niches.exists(request.homeNicheId())) {
            throw new NotFoundException(NicheService.NICHE_NOT_FOUND, "Niche not found.");
        }
        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        UUID id;
        try {
            id = communities.create(me, request.name().strip(),
                    request.description() == null || request.description().isBlank() ? null : request.description().strip(),
                    request.emoji() == null ? DEFAULT_EMOJI : request.emoji(),
                    request.coverColor() == null ? DEFAULT_COLOR : request.coverColor(),
                    request.homeNicheId(), Boolean.TRUE.equals(request.isPrivate()), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(COMMUNITY_NAME_TAKEN, "A community with that name exists.");
        }
        if (request.rules() != null) {
            int position = 1;
            for (String rule : request.rules()) {
                communities.addRule(id, position++, rule.strip());
            }
        }
        return toResponse(communities.find(me, id).orElseThrow(), null);
    }

    /**
     * Joins a public community, or asks to join a private one (an invitation joins it at once).
     * Asking twice, or joining one already joined, is not an error.
     */
    @Transactional
    public JoinState join(UUID id) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if (communities.isBanned(id, me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, BANNED_FROM_COMMUNITY, "You cannot join this community.");
        }
        Instant now = clock.instant();
        if (row.myRole() != null) {
            return new JoinState("joined", row.members());
        }
        profiles.ensureExists(me, now);
        if (!row.isPrivate() || communities.isInvited(id, me)) {
            communities.addMember(id, me, "member", now);
            communities.removeRequest(id, me);
            return new JoinState("joined", communities.find(me, id).orElseThrow().members());
        }
        communities.addRequest(id, me, now);
        return new JoinState("requested", row.members());
    }

    /** Leaves the community, or withdraws the request. The founder cannot leave (409 {@code FOUNDER_CANNOT_LEAVE}). */
    @Transactional
    public JoinState leave(UUID id) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if ("founder".equals(row.myRole())) {
            throw new ConflictException(FOUNDER_CANNOT_LEAVE, "The founder cannot leave their community.");
        }
        communities.removeMember(id, me);
        communities.removeRequest(id, me);
        return new JoinState("none", communities.find(me, id).orElseThrow().members());
    }

    /**
     * {@code filter}: {@code all} (default), {@code mods}, or {@code requests} (moderators only). A
     * private community's members are for its members only.
     */
    @Transactional(readOnly = true)
    public CursorPage<MemberItem> members(UUID id, String filter, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        String chosen = filter == null || filter.isBlank() ? "all" : filter;
        if (!Set.of("all", "mods", "requests").contains(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FILTER, "filter must be all, mods or requests.");
        }
        if ("requests".equals(chosen)) {
            requireModerator(row);
        } else if (row.isPrivate() && row.myRole() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_COMMUNITY_MEMBER, "Join this community to see its members.");
        }
        int limit = Paging.limit(requestedLimit);
        Cursor.TimeId after = Cursor.TimeId.decode(cursor);
        Instant t = after == null ? null : after.time();
        UUID afterId = after == null ? null : after.id();
        List<Member> fetched = "requests".equals(chosen)
                ? communities.requests(me, id, t, afterId, limit + 1)
                : communities.members(me, id, "mods".equals(chosen), t, afterId, limit + 1);
        List<Member> page = Paging.head(fetched, limit);
        Map<UUID, UserBrief> loaded = briefs.load(page.stream().map(Member::userId).toList());
        List<MemberItem> items = page.stream().map(m -> {
            UserBrief u = briefs.loadOrPlaceholder(m.userId(), loaded);
            return new MemberItem(u.id(), u.name(), u.handle(), u.avatarUrl(), u.verified(), m.role(), m.at(),
                    u.id().equals(me));
        }).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, m -> new Cursor.TimeId(m.at(), m.userId()).encode()));
    }

    /** Only the founder changes roles, and only between moderator and member. */
    @Transactional
    public MemberItem changeRole(UUID id, UUID userId, String role) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if (!"founder".equals(row.myRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Only the founder can change roles.");
        }
        String current = communities.roleOf(id, userId).orElseThrow(CommunityService::memberNotFound);
        if ("founder".equals(current)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_FOUNDER", "The founder's role cannot change.");
        }
        communities.setRole(id, userId, role);
        return memberItem(me, id, userId);
    }

    /**
     * Removes a member. The founder can remove anyone but themself; a moderator can remove members
     * but not other moderators or the founder.
     */
    @Transactional
    public void removeMember(UUID id, UUID userId) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        requireModerator(row);
        String target = communities.roleOf(id, userId).orElseThrow(CommunityService::memberNotFound);
        boolean founder = "founder".equals(row.myRole());
        if ("founder".equals(target) || (!founder && "moderator".equals(target))) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "You cannot remove this member.");
        }
        communities.removeMember(id, userId);
    }

    @Transactional
    public MemberItem approve(UUID id, UUID userId) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        requireModerator(row);
        if (!communities.removeRequest(id, userId)) {
            throw new NotFoundException(REQUEST_NOT_FOUND, "That request is not there any more.");
        }
        if (communities.isBanned(id, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, BANNED_FROM_COMMUNITY, "That person is banned from this community.");
        }
        communities.addMember(id, userId, "member", clock.instant());
        return memberItem(me, id, userId);
    }

    @Transactional
    public void deny(UUID id, UUID userId) {
        Row row = communities.find(CurrentUser.id(), id).orElseThrow(CommunityService::notFound);
        requireModerator(row);
        if (!communities.removeRequest(id, userId)) {
            throw new NotFoundException(REQUEST_NOT_FOUND, "That request is not there any more.");
        }
    }

    /**
     * Invites people. Members can invite to a public community; only moderators to a private one.
     * People who are already in, blocked either way, or not active are skipped.
     */
    @Transactional
    public InviteResult invite(UUID id, List<UUID> userIds) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if (row.myRole() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_COMMUNITY_MEMBER, "Join this community to invite people.");
        }
        if (row.isPrivate()) {
            requireModerator(row);
        }
        Set<UUID> candidates = new java.util.LinkedHashSet<>(userIds);
        candidates.remove(me);
        Set<UUID> active = users.activeAmong(candidates);
        Set<UUID> blocked = blocks.blockedEitherWay(me, candidates);
        int invited = 0;
        for (UUID candidate : candidates) {
            if (active.contains(candidate) && !blocked.contains(candidate) && communities.roleOf(id, candidate).isEmpty()
                    && !communities.isBanned(id, candidate) && communities.invite(id, me, candidate)) {
                invited++;
            }
        }
        return new InviteResult(invited);
    }

    /** A link to share. Public communities for any member; private ones for moderators. */
    @Transactional(readOnly = true)
    public Map<String, String> inviteLink(UUID id) {
        UUID me = CurrentUser.id();
        Row row = communities.find(me, id).orElseThrow(CommunityService::notFound);
        if (row.myRole() == null || (row.isPrivate() && !isModerator(row))) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "You cannot share a link to this community.");
        }
        return Map.of("url", app.baseUrl() + "/c/" + id);
    }

    private MemberItem memberItem(UUID me, UUID communityId, UUID userId) {
        UserBrief u = briefs.loadOrPlaceholder(userId, briefs.load(List.of(userId)));
        String role = communities.roleOf(communityId, userId).orElse(null);
        return new MemberItem(u.id(), u.name(), u.handle(), u.avatarUrl(), u.verified(), role, clock.instant(),
                u.id().equals(me));
    }

    private CommunityResponse toResponse(Row row, List<UserBrief> faces) {
        return new CommunityResponse(row.id(), row.name(), row.description(), row.members(), row.emoji(),
                row.coverColor(), urls.urlFor(row.iconKey()), row.homeNicheId(), row.homeNiche(), row.isPrivate(),
                row.myRole() != null, row.requested(), row.myRole(), faces);
    }

    private void requireModerator(Row row) {
        if (!isModerator(row)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Only moderators can do this.");
        }
    }

    private static boolean isModerator(Row row) {
        return "founder".equals(row.myRole()) || "moderator".equals(row.myRole());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(COMMUNITY_NOT_FOUND, "Community not found.");
    }

    private static NotFoundException memberNotFound() {
        return new NotFoundException(MEMBER_NOT_FOUND, "That person is not a member.");
    }
}
