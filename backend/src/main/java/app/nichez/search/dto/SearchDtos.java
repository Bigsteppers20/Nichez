package app.nichez.search.dto;

import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.niche.dto.CommunityDtos.CommunityResponse;
import app.nichez.post.dto.PostResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Responses for Explore and search. */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** A niche in the trending list: the app's {@code TrendingNiche}. {@code joinedToday} is people who joined in the last 24 hours. */
    public record TrendingNiche(UUID id, String name, int members, int joinedToday, String iconUrl) {
    }

    /** One line of type-ahead. {@code kind} is niche, subThread (a community), person or query. {@code id} is null for a past query. */
    public record Suggestion(String title, String subtitle, String kind, UUID id) {
    }

    /** What to offer when nothing matched: the busiest niche the person has not joined. */
    public record PromotedNiche(UUID id, String name, int members, int postsToday, boolean joined) {
    }

    public record PromotedResponse(PromotedNiche promotedNiche) {
    }

    public record NicheResult(UUID id, String name, String slug, int members, boolean joined) {
    }

    /**
     * One search result. {@code kind} says which of the other fields is set: post, niche, person or
     * community. The rest are left out of the JSON.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResult(String kind, PostResponse post, NicheResult niche, PersonItem person,
            CommunityResponse community) {
    }

    /** A page of results: {@code { items, nextCursor }} plus {@code promotedNiche} when there were no results at all. */
    public record SearchPage(List<SearchResult> items, String nextCursor,
            @JsonProperty("promotedNiche") @JsonInclude(JsonInclude.Include.NON_NULL) PromotedNiche promotedNiche) {
    }
}
