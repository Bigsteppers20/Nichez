package app.nichez.post.service;

import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.Paging;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.service.UserBriefs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns the ids a list query found into a page of post cards with the cursor for the next page. */
@Component
public class PostPages {

    private final PostCards cards;
    private final UserBriefs briefs;

    PostPages(PostCards cards, UserBriefs briefs) {
        this.cards = cards;
        this.briefs = briefs;
    }

    /** {@code fetched} holds up to {@code limit + 1} items; the extra one only says there is more. */
    public CursorPage<PostResponse> page(UUID me, List<Item> fetched, int limit) {
        List<Item> page = Paging.head(fetched, limit);
        List<PostResponse> loaded = cards.load(me, page.stream().map(Item::postId).toList());
        Map<UUID, UserBrief> resharers = briefs.load(page.stream().map(Item::resharedBy)
                .filter(java.util.Objects::nonNull).toList());
        Map<UUID, Item> byPost = new java.util.HashMap<>();
        page.forEach(item -> byPost.put(item.postId(), item));
        List<PostResponse> items = loaded.stream().map(card -> {
            Item item = byPost.get(card.id());
            if (item == null || item.resharedBy() == null) {
                return card;
            }
            UserBrief who = briefs.loadOrPlaceholder(item.resharedBy(), resharers);
            return card.withResharedBy(new PostResponse.ResharedBy(who.id(), who.name(), who.handle()));
        }).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit,
                item -> new Cursor.TimeId(item.at(), item.postId()).encode()));
    }
}
