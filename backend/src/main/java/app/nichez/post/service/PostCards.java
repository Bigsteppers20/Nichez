package app.nichez.post.service;

import app.nichez.common.TimeAgo;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostCardRepository;
import app.nichez.post.repository.PostCardRepository.Row;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.service.UserBriefs;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds {@link PostResponse}s for posts the caller has already been allowed to see. Every list
 * of posts (feed, profiles, saved, search, communities) ends here, so a card looks the same
 * everywhere and the viewer's own flags are always computed from the session.
 */
@Component
public class PostCards {

    private final PostCardRepository cards;
    private final UserBriefs briefs;
    private final MediaUrlProvider urls;
    private final Clock clock;

    PostCards(PostCardRepository cards, UserBriefs briefs, MediaUrlProvider urls, Clock clock) {
        this.cards = cards;
        this.briefs = briefs;
        this.urls = urls;
        this.clock = clock;
    }

    /** One card per id, in the order given. An id that no longer exists is skipped. */
    public List<PostResponse> load(UUID me, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Row> rows = new HashMap<>();
        cards.rows(me, ids).forEach(row -> rows.put(row.id(), row));
        Map<UUID, Map<String, Integer>> breakdown = cards.reactionBreakdown(rows.keySet());
        Map<UUID, UserBrief> authors = briefs.load(rows.values().stream().map(Row::authorId).toList());
        Instant now = clock.instant();
        List<PostResponse> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Row row = rows.get(id);
            if (row != null) {
                result.add(toResponse(me, row, authors, breakdown.getOrDefault(id, Map.of()), now));
            }
        }
        return result;
    }

    public Optional<PostResponse> loadOne(UUID me, UUID id) {
        return load(me, List.of(id)).stream().findFirst();
    }

    private PostResponse toResponse(UUID me, Row row, Map<UUID, UserBrief> authors, Map<String, Integer> breakdown,
            Instant now) {
        UserBrief author = briefs.loadOrPlaceholder(row.authorId(), authors);
        // Until processing finishes there is no final file; the author's preview is their own upload.
        String mediaKey = row.finalKey() != null ? row.finalKey() : row.originalKey();
        return new PostResponse(
                row.id(),
                new PostResponse.Author(author.id(), author.name(), author.handle(), author.avatarUrl()),
                row.nicheId(),
                row.nicheLabel(),
                row.communityId(),
                row.caption(),
                row.audience(),
                row.allowComments(),
                row.status(),
                "video".equals(row.mediaKind()),
                row.hasAudio(),
                urls.urlFor(mediaKey),
                urls.urlFor(row.posterKey()),
                row.sensitive(),
                row.sensitiveReason(),
                row.reactions(),
                row.comments(),
                row.reshares(),
                row.shares(),
                row.saves(),
                row.views(),
                Map.copyOf(breakdown),
                row.myReaction(),
                row.saved(),
                row.authorId().equals(me),
                null,
                null,
                row.createdAt(),
                TimeAgo.between(row.createdAt(), now));
    }
}
