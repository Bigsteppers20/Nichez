package app.nichez.search.controller;

import app.nichez.common.CursorPage;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.post.dto.PostResponse;
import app.nichez.search.dto.SearchDtos.PromotedResponse;
import app.nichez.search.dto.SearchDtos.SearchPage;
import app.nichez.search.dto.SearchDtos.Suggestion;
import app.nichez.search.dto.SearchDtos.TrendingNiche;
import app.nichez.search.service.SearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explore and search. Signed-in users only. */
@RestController
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/api/v1/explore/trending")
    CursorPage<TrendingNiche> trending() {
        return search.trending();
    }

    @GetMapping("/api/v1/explore/recent")
    CursorPage<PostResponse> recent(@RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return search.recent(cursor, limit);
    }

    /** {@code q} may be empty: then only the caller's past searches come back. */
    @GetMapping("/api/v1/search/suggest")
    CursorPage<Suggestion> suggest(@RequestParam(value = "q", required = false, defaultValue = "") @Size(max = 80) String q) {
        return search.suggest(q);
    }

    @GetMapping("/api/v1/search")
    SearchPage results(@RequestParam("q") @NotBlank @Size(max = 80) String q,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return search.results(q, type, cursor, limit);
    }

    @GetMapping("/api/v1/niches/promoted")
    PromotedResponse promoted(@RequestParam(value = "q", required = false) @Size(max = 80) String q) {
        return search.promoted();
    }

    /** People to tag in a post. */
    @GetMapping("/api/v1/users/search")
    CursorPage<PersonItem> people(@RequestParam("q") @NotBlank @Size(max = 80) String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return search.people(q, cursor, limit);
    }
}
