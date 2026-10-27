package app.nichez.feed.controller;

import app.nichez.common.CursorPage;
import app.nichez.feed.service.FeedService;
import app.nichez.niche.dto.NicheDtos.MyNiche;
import app.nichez.niche.service.NicheService;
import app.nichez.post.dto.PostResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The home feed and a niche's feed. Signed-in users only. */
@RestController
class FeedController {

    private final FeedService feed;
    private final NicheService niches;

    FeedController(FeedService feed, NicheService niches) {
        this.feed = feed;
        this.niches = niches;
    }

    @GetMapping("/api/v1/feed")
    CursorPage<PostResponse> home(@RequestParam(value = "niche", required = false) UUID niche,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return feed.home(niche, cursor, limit);
    }

    /** The tab strip above the feed: the niches the caller belongs to. */
    @GetMapping("/api/v1/feed/niches")
    CursorPage<MyNiche> tabs() {
        return niches.mine();
    }

    @GetMapping("/api/v1/niches/{id}/feed")
    CursorPage<PostResponse> nicheFeed(@PathVariable("id") UUID id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return feed.home(id, cursor, limit);
    }
}
