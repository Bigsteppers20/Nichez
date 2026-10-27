package app.nichez.feed.service;

import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.post.service.PostPages;
import app.nichez.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The home feed. Newest first: posts from the caller's niches and the people they follow, their own,
 * and what the people they follow reshared. Left out for this caller: muted people, blocks either
 * way, posts marked "not interested", posts they reported, and sensitive posts if they chose to hide them.
 */
@Service
public class FeedService {

    public static final String NICHE_NOT_FOUND = "NICHE_NOT_FOUND";

    private final PostListRepository lists;
    private final PostPages pages;
    private final NicheRepository niches;

    FeedService(PostListRepository lists, PostPages pages, NicheRepository niches) {
        this.lists = lists;
        this.pages = pages;
        this.niches = niches;
    }

    /** With {@code nicheId}, only that niche (404 {@code NICHE_NOT_FOUND} when there is none). */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> home(UUID nicheId, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        if (nicheId != null && !niches.exists(nicheId)) {
            throw new NotFoundException(NICHE_NOT_FOUND, "Niche not found.");
        }
        int limit = Paging.limit(requestedLimit);
        List<Item> fetched = lists.feed(me, nicheId, Cursor.TimeId.decode(cursor), limit + 1);
        return pages.page(me, fetched, limit);
    }
}
