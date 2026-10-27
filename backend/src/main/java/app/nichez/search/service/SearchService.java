package app.nichez.search.service;

import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.Paging;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.common.SqlLike;
import app.nichez.config.SocialProperties;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.niche.dto.CommunityDtos.CommunityResponse;
import app.nichez.niche.repository.CommunityRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.post.service.PostPages;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.service.UserBriefs;
import app.nichez.search.dto.SearchDtos.NicheResult;
import app.nichez.search.dto.SearchDtos.PromotedNiche;
import app.nichez.search.dto.SearchDtos.PromotedResponse;
import app.nichez.search.dto.SearchDtos.SearchPage;
import app.nichez.search.dto.SearchDtos.SearchResult;
import app.nichez.search.dto.SearchDtos.Suggestion;
import app.nichez.search.dto.SearchDtos.TrendingNiche;
import app.nichez.search.repository.SearchRepository;
import app.nichez.search.repository.SearchRepository.NicheRow;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explore, type-ahead and search. Posts, people and communities are shown only as the caller may see
 * them (no blocks, no deactivated accounts, no private posts). Who is asking always comes from the token.
 */
@Service
public class SearchService {

    public static final String INVALID_TYPE = "INVALID_TYPE";

    private static final Set<String> TYPES = Set.of("top", "memes", "niches", "people");
    private static final int TRENDING = 20;
    private static final int TOP_GROUP = 3;

    private final SearchRepository search;
    private final PostListRepository posts;
    private final PostPages pages;
    private final CommunityRepository communities;
    private final FollowRepository follows;
    private final UserBriefs briefs;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    SearchService(SearchRepository search, PostListRepository posts, PostPages pages, CommunityRepository communities,
            FollowRepository follows, UserBriefs briefs, SlidingWindowRateLimiter limiter, SocialProperties social,
            Clock clock) {
        this.search = search;
        this.posts = posts;
        this.pages = pages;
        this.communities = communities;
        this.follows = follows;
        this.briefs = briefs;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    /** Niches ordered by who joined in the last 24 hours, then by size. */
    @Transactional(readOnly = true)
    public CursorPage<TrendingNiche> trending() {
        List<TrendingNiche> items = search.trending(CurrentUser.id(), clock.instant().minus(Duration.ofHours(24)), TRENDING)
                .stream()
                .map(n -> new TrendingNiche(n.id(), n.label(), n.members(), n.joinedToday(), null))
                .toList();
        return new CursorPage<>(items, null);
    }

    /** The Explore grid: recent posts the caller can see, across niches. */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> recent(String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        int limit = Paging.limit(requestedLimit);
        List<Item> fetched = posts.recent(me, Cursor.TimeId.decode(cursor), limit + 1);
        return pages.page(me, fetched, limit);
    }

    /** Type-ahead: niches, then communities, then people, then the caller's own past searches. */
    @Transactional(readOnly = true)
    public CursorPage<Suggestion> suggest(String q) {
        UUID me = CurrentUser.id();
        limiter.check("search:" + me, social.searchesPerMinute(), Duration.ofMinutes(1));
        String text = normalise(q);
        List<Suggestion> items = new ArrayList<>();
        if (!text.isEmpty()) {
            String contains = SqlLike.contains(text);
            String prefix = SqlLike.startsWith(text);
            for (NicheRow n : search.niches(me, contains, prefix, 0, 4)) {
                items.add(new Suggestion(n.label(), n.members() + " members", "niche", n.id()));
            }
            for (CommunityRepository.Row c : communities.search(me, contains, 0, 3)) {
                items.add(new Suggestion(c.name(), c.members() + " members in " + c.homeNiche(), "subThread", c.id()));
            }
            List<UUID> people = search.people(me, text, contains, prefix, 0, 4);
            Map<UUID, UserBrief> loaded = briefs.load(people);
            for (UUID id : people) {
                UserBrief u = briefs.loadOrPlaceholder(id, loaded);
                items.add(new Suggestion(u.name() != null ? u.name() : u.handle(), "@" + u.handle(), "person", id));
            }
        }
        String historyPrefix = text.isEmpty() ? "%" : SqlLike.startsWith(text);
        for (String past : search.history(me, historyPrefix, 3)) {
            items.add(new Suggestion(past, "", "query", null));
        }
        return new CursorPage<>(items, null);
    }

    /**
     * Results for {@code q}. {@code type} is top (default: a few niches, communities and people, then
     * posts), memes (posts), niches or people. Pages carry an offset in the cursor. When a first page
     * finds nothing, {@code promotedNiche} offers a niche to join.
     */
    @Transactional
    public SearchPage results(String q, String type, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        limiter.check("search:" + me, social.searchesPerMinute(), Duration.ofMinutes(1));
        String chosen = type == null || type.isBlank() ? "top" : type;
        if (!TYPES.contains(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_TYPE, "type must be top, memes, niches or people.");
        }
        String text = normalise(q);
        int limit = Paging.limit(requestedLimit);
        List<String> parts = Cursor.decode(cursor, 1);
        int offset = parts == null ? 0 : (int) Cursor.number(parts.get(0));
        String contains = SqlLike.contains(text);
        String prefix = SqlLike.startsWith(text);
        List<SearchResult> items = new ArrayList<>();
        boolean more = false;

        if (offset == 0 && text.length() >= 2) {
            search.remember(me, text.toLowerCase(Locale.ROOT), clock.instant());
        }
        if ("top".equals(chosen) && offset == 0) {
            for (NicheRow n : search.niches(me, contains, prefix, 0, TOP_GROUP)) {
                items.add(nicheResult(n));
            }
            for (CommunityRepository.Row c : communities.search(me, contains, 0, TOP_GROUP)) {
                items.add(communityResult(c));
            }
            items.addAll(personResults(me, search.people(me, text, contains, prefix, 0, TOP_GROUP)));
        }
        switch (chosen) {
            case "niches" -> {
                List<NicheRow> found = search.niches(me, contains, prefix, offset, limit + 1);
                more = found.size() > limit;
                Paging.head(found, limit).forEach(n -> items.add(nicheResult(n)));
            }
            case "people" -> {
                List<UUID> found = search.people(me, text, contains, prefix, offset, limit + 1);
                more = found.size() > limit;
                items.addAll(personResults(me, Paging.head(found, limit)));
            }
            default -> {
                List<Item> found = posts.search(me, contains, false, offset, limit + 1);
                more = found.size() > limit;
                for (PostResponse post : pages.page(me, found, limit).items()) {
                    items.add(new SearchResult("post", post, null, null, null));
                }
            }
        }
        String next = more ? Cursor.encode(offset + limit) : null;
        PromotedNiche promoted = items.isEmpty() && offset == 0 ? promotedFor(me) : null;
        return new SearchPage(items, next, promoted);
    }

    /** The busiest niche the caller has not joined. */
    @Transactional(readOnly = true)
    public PromotedResponse promoted() {
        return new PromotedResponse(promotedFor(CurrentUser.id()));
    }

    /** People to tag in a post, or to find: handle or name matches. */
    @Transactional(readOnly = true)
    public CursorPage<PersonItem> people(String q, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        limiter.check("search:" + me, social.searchesPerMinute(), Duration.ofMinutes(1));
        String text = normalise(q);
        int limit = Paging.limit(requestedLimit);
        List<String> parts = Cursor.decode(cursor, 1);
        int offset = parts == null ? 0 : (int) Cursor.number(parts.get(0));
        List<UUID> found = search.people(me, text, SqlLike.contains(text), SqlLike.startsWith(text), offset, limit + 1);
        List<UUID> page = Paging.head(found, limit);
        List<PersonItem> items = personResults(me, page).stream().map(SearchResult::person).toList();
        return new CursorPage<>(items, found.size() > limit ? Cursor.encode(offset + limit) : null);
    }

    private PromotedNiche promotedFor(UUID me) {
        return search.promoted(me, clock.instant().minus(Duration.ofHours(24)))
                .map(n -> new PromotedNiche(n.id(), n.label(), n.members(), n.joinedToday(), false))
                .orElse(null);
    }

    private List<SearchResult> personResults(UUID me, List<UUID> ids) {
        Map<UUID, UserBrief> loaded = briefs.load(ids);
        Set<UUID> followed = follows.followedAmong(me, ids);
        return ids.stream().map(id -> {
            UserBrief u = briefs.loadOrPlaceholder(id, loaded);
            return new SearchResult("person", null, null, new PersonItem(u.id(), u.name(), u.handle(), u.avatarUrl(),
                    u.verified(), followed.contains(id), id.equals(me)), null);
        }).toList();
    }

    private static SearchResult nicheResult(NicheRow n) {
        return new SearchResult("niche", null, new NicheResult(n.id(), n.label(), n.slug(), n.members(), n.joined()),
                null, null);
    }

    private SearchResult communityResult(CommunityRepository.Row c) {
        return new SearchResult("community", null, null, null, new CommunityResponse(c.id(), c.name(), c.description(),
                c.members(), c.emoji(), c.coverColor(), null, c.homeNicheId(), c.homeNiche(), c.isPrivate(),
                c.myRole() != null, c.requested(), c.myRole(), null));
    }

    private static String normalise(String q) {
        return q == null ? "" : q.strip().replaceAll("\\s+", " ");
    }
}
