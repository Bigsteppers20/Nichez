package app.nichez.post.service;

import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.Paging;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.niche.repository.CommunityRepository;
import app.nichez.niche.repository.NicheCatalogRepository;
import app.nichez.post.dto.PostExtrasDtos.AnalyticsTotals;
import app.nichez.post.dto.PostExtrasDtos.MyAnalytics;
import app.nichez.post.dto.PostExtrasDtos.PostAnalytics;
import app.nichez.post.dto.PostExtrasDtos.PostStatusResponse;
import app.nichez.post.dto.PostExtrasDtos.PostTarget;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.entity.Post;
import app.nichez.post.repository.PostAnalyticsRepository;
import app.nichez.post.repository.PostAnalyticsRepository.Totals;
import app.nichez.post.repository.PostAnalyticsRepository.ViewedPost;
import app.nichez.post.repository.PostAnalyticsRepository.Weekly;
import app.nichez.post.repository.PostRepository;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the caller can post, how a post is getting on while it processes, and the numbers behind
 * posts. Numbers are for the author only: anyone else gets the same 404 {@code POST_NOT_FOUND} as for
 * a post that does not exist. Who is asking always comes from the token.
 */
@Service
public class PostInsightsService {

    private final NicheCatalogRepository niches;
    private final CommunityRepository communities;
    private final PostAccess access;
    private final PostRepository posts;
    private final PostAnalyticsRepository analytics;
    private final PostCards cards;
    private final FollowRepository follows;
    private final Clock clock;

    PostInsightsService(NicheCatalogRepository niches, CommunityRepository communities, PostAccess access,
            PostRepository posts, PostAnalyticsRepository analytics, PostCards cards, FollowRepository follows,
            Clock clock) {
        this.niches = niches;
        this.communities = communities;
        this.access = access;
        this.posts = posts;
        this.analytics = analytics;
        this.cards = cards;
        this.follows = follows;
        this.clock = clock;
    }

    /** The niches the caller joined and the communities they belong to, in that order. */
    @Transactional(readOnly = true)
    public CursorPage<PostTarget> targets() {
        UUID me = CurrentUser.id();
        List<PostTarget> items = new java.util.ArrayList<>();
        niches.joinedBy(me).forEach(n -> items.add(new PostTarget("niche", n.id(), n.label(), n.id())));
        communities.joined(me, null, null, null, 100).forEach(c ->
                items.add(new PostTarget("community", c.id(), c.name(), c.homeNicheId())));
        return new CursorPage<>(items, null);
    }

    /** Where a post is in processing; the same visibility as {@code GET /posts/:id}. */
    @Transactional(readOnly = true)
    public PostStatusResponse status(UUID postId) {
        UUID me = CurrentUser.id();
        access.requireReadable(me, postId);
        Post post = posts.findById(postId).orElseThrow(PostAccess::notFound);
        return new PostStatusResponse(post.getId(), post.getStatus().dbValue());
    }

    @Transactional(readOnly = true)
    public PostAnalytics postAnalytics(UUID postId) {
        UUID me = CurrentUser.id();
        Post post = posts.findById(postId).filter(p -> p.getAuthorId().equals(me)).orElseThrow(PostAccess::notFound);
        PostResponse card = cards.loadOne(me, postId).orElseThrow(PostAccess::notFound);
        Weekly week = analytics.weekly(postId, today());
        long views = card.views();
        return new PostAnalytics(postId, views, week.thisWeek(), week.lastWeek(), change(week),
                views == 0 ? null : (double) (card.reactions() + card.comments() + card.reshares()) / views,
                card.reactions(), card.comments(), card.reshares(), card.shares(), card.saves(), card.reactionCounts());
    }

    /** The caller's totals and their published posts, most viewed first. */
    @Transactional(readOnly = true)
    public MyAnalytics myAnalytics(String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        int limit = Paging.limit(requestedLimit);
        List<String> parts = Cursor.decode(cursor, 2);
        List<ViewedPost> fetched = analytics.mostViewed(me, parts == null ? null : Cursor.number(parts.get(0)),
                parts == null ? null : Cursor.uuid(parts.get(1)), limit + 1);
        List<ViewedPost> page = Paging.head(fetched, limit);
        Map<UUID, PostResponse> byId = new java.util.HashMap<>();
        cards.load(me, page.stream().map(ViewedPost::postId).toList()).forEach(c -> byId.put(c.id(), c));
        List<PostResponse> items = page.stream().map(v -> byId.get(v.postId())).filter(java.util.Objects::nonNull).toList();
        Totals totals = analytics.totals(me);
        Weekly week = analytics.weeklyOf(me, today());
        int followers = follows.followerCount(me);
        return new MyAnalytics(new AnalyticsTotals(totals.views(), week.thisWeek(), week.lastWeek(), totals.reactions(),
                totals.comments(), totals.reshares(), totals.shares(), totals.saves(), totals.posts(), followers),
                items, Paging.nextCursor(fetched, limit, v -> Cursor.encode(v.views(), v.postId())));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Double change(Weekly week) {
        return week.lastWeek() == 0 ? null : (double) (week.thisWeek() - week.lastWeek()) / week.lastWeek();
    }
}
