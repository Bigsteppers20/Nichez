package app.nichez.sharing.service;

import app.nichez.activity.service.Notifier;
import app.nichez.common.ApiException;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.AppProperties;
import app.nichez.config.SocialProperties;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.niche.repository.NicheCatalogRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.entity.Audience;
import app.nichez.post.entity.Post;
import app.nichez.post.repository.PostVisibilityRepository;
import app.nichez.post.service.PostAccess;
import app.nichez.post.service.PostCards;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.service.UserBriefs;
import app.nichez.security.CurrentUser;
import app.nichez.sharing.dto.SharingDtos.CrossPostResponse;
import app.nichez.sharing.dto.SharingDtos.LinkResponse;
import app.nichez.sharing.dto.SharingDtos.PublicPost;
import app.nichez.sharing.dto.SharingDtos.ReshareResponse;
import app.nichez.sharing.dto.SharingDtos.ShareCount;
import app.nichez.sharing.repository.SharingRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending a post out: counting shares, reshares to followers, cross-posts into another niche,
 * "not interested", share links, and who to offer in the share sheet. Who is asking always comes
 * from the token, and the post must be one the caller can see.
 */
@Service
public class SharingService {

    public static final String CANNOT_RESHARE_OWN_POST = "CANNOT_RESHARE_OWN_POST";
    public static final String POST_NOT_SHAREABLE = "POST_NOT_SHAREABLE";
    public static final String ALREADY_IN_NICHE = "ALREADY_IN_NICHE";
    public static final String NOT_A_NICHE_MEMBER = "NOT_A_NICHE_MEMBER";
    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";

    /** The signed-out viewer: a user id nothing references, so no block, membership or follow applies to it. */
    private static final UUID ANONYMOUS = new UUID(0L, 0L);
    private static final int SHARE_TARGETS = 20;

    private final SharingRepository sharing;
    private final PostAccess access;
    private final PostCards cards;
    private final PostVisibilityRepository visibility;
    private final NicheCatalogRepository niches;
    private final FollowRepository follows;
    private final UserBriefs briefs;
    private final Notifier notifier;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final AppProperties app;
    private final Clock clock;

    SharingService(SharingRepository sharing, PostAccess access, PostCards cards, PostVisibilityRepository visibility,
            NicheCatalogRepository niches, FollowRepository follows, UserBriefs briefs, Notifier notifier,
            SlidingWindowRateLimiter limiter, SocialProperties social, AppProperties app, Clock clock) {
        this.sharing = sharing;
        this.access = access;
        this.cards = cards;
        this.visibility = visibility;
        this.niches = niches;
        this.follows = follows;
        this.briefs = briefs;
        this.notifier = notifier;
        this.limiter = limiter;
        this.social = social;
        this.app = app;
        this.clock = clock;
    }

    /** Counts a share-out. Posts open only to a niche's members can still be shared as a link to those members. */
    @Transactional
    public ShareCount recordShare(UUID postId, String channel) {
        UUID me = CurrentUser.id();
        access.requireInteractable(me, postId);
        sharing.insertShare(postId, me, channel, clock.instant());
        return new ShareCount(sharing.shareCount(postId));
    }

    /** The public address of a post. */
    @Transactional(readOnly = true)
    public LinkResponse link(UUID postId) {
        access.requireInteractable(CurrentUser.id(), postId);
        return new LinkResponse(app.baseUrl() + "/p/" + ShareLinks.encode(postId));
    }

    /**
     * Reshares a post to the caller's followers. Only posts open to everyone can leave their niche
     * (403 {@code POST_NOT_SHAREABLE}), and not your own (400 {@code CANNOT_RESHARE_OWN_POST}).
     * Resharing twice is not an error and counts once.
     */
    @Transactional
    public ReshareResponse reshare(UUID postId) {
        UUID me = CurrentUser.id();
        Post post = access.requireInteractable(me, postId);
        if (post.getAuthorId().equals(me)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CANNOT_RESHARE_OWN_POST, "You cannot reshare your own post.");
        }
        requireShareable(post);
        if (sharing.insertReshare(postId, me, clock.instant())) {
            notifier.reshared(me, post);
        }
        return new ReshareResponse(true, sharing.reshareCount(postId));
    }

    /**
     * Shows a post in another niche too. The caller must have joined that niche (403
     * {@code NOT_A_NICHE_MEMBER}) and not be banned from it; the post must be open to everyone, and
     * must not already belong to that niche (409 {@code ALREADY_IN_NICHE}).
     */
    @Transactional
    public CrossPostResponse crossPost(UUID postId, UUID nicheId) {
        UUID me = CurrentUser.id();
        Post post = access.requireInteractable(me, postId);
        niches.find(nicheId).orElseThrow(() -> new NotFoundException(NICHE_NOT_FOUND, "Niche not found."));
        if (!niches.joinedIds(me).contains(nicheId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_NICHE_MEMBER, "Join this niche before sharing to it.");
        }
        if (niches.isBanned(me, nicheId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BANNED_FROM_NICHE", "You cannot share to this niche.");
        }
        requireShareable(post);
        if (post.getNicheId().equals(nicheId) || !sharing.insertCrossPost(postId, nicheId, me, clock.instant())) {
            throw new app.nichez.common.ConflictException(ALREADY_IN_NICHE, "This post is already in that niche.");
        }
        sharing.insertShare(postId, me, "cross_post", clock.instant());
        notifier.reshared(me, post);
        return new CrossPostResponse(postId, nicheId, sharing.shareCount(postId));
    }

    /** Tells the feed to show fewer posts like this one. */
    @Transactional
    public void notInterested(UUID postId) {
        UUID me = CurrentUser.id();
        access.requireInteractable(me, postId);
        sharing.markNotInterested(me, postId, clock.instant());
    }

    /** People for the "Send to" row. */
    @Transactional(readOnly = true)
    public CursorPage<PersonItem> shareTargets() {
        UUID me = CurrentUser.id();
        List<UUID> ids = sharing.shareTargets(me, SHARE_TARGETS);
        Map<UUID, UserBrief> loaded = briefs.load(ids);
        Set<UUID> followed = follows.followedAmong(me, ids);
        List<PersonItem> items = ids.stream().map(id -> {
            UserBrief u = briefs.loadOrPlaceholder(id, loaded);
            return new PersonItem(u.id(), u.name(), u.handle(), u.avatarUrl(), u.verified(), followed.contains(id), false);
        }).toList();
        return new CursorPage<>(items, null);
    }

    /**
     * Resolves a share link for anyone, signed in or not. A code that does not decode, a post that
     * is not public, and a post that no longer exists are all the same 404 {@code LINK_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public PublicPost resolve(String code, String ipAddress) {
        limiter.check("link:" + ipAddress, social.publicLookupsPerMinute(), Duration.ofMinutes(1));
        UUID postId = ShareLinks.decode(code).orElseThrow(SharingService::linkNotFound);
        if (!visibility.canInteract(ANONYMOUS, postId)) {
            throw linkNotFound();
        }
        PostResponse post = cards.loadOne(ANONYMOUS, postId).orElseThrow(SharingService::linkNotFound);
        return new PublicPost(post.id(), post.author().name(), post.author().handle(), post.author().avatarUrl(),
                post.niche(), post.caption(), post.isVideo(), post.mediaUrl(), post.posterUrl(), post.reactions(),
                post.comments(), post.createdAt(), post.timeAgo());
    }

    private static final String NICHE_NOT_FOUND = "NICHE_NOT_FOUND";

    private static void requireShareable(Post post) {
        if (post.getAudience() != Audience.EVERYONE) {
            throw new ApiException(HttpStatus.FORBIDDEN, POST_NOT_SHAREABLE,
                    "This post is only for members of its niche.");
        }
    }

    private static NotFoundException linkNotFound() {
        return new NotFoundException(LINK_NOT_FOUND, "This link is not available.");
    }
}
