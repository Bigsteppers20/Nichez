package app.nichez.niche.dto;

/**
 * {@code GET /niches/:id/stats}, for G4 Published.
 *
 * @param postsToday  published posts in the niche over the last 24 hours
 * @param freshWindow how long a new post can expect to top Fresh, e.g. "the next 20 minutes"
 */
public record NicheStatsResponse(long postsToday, String freshWindow) {
}
