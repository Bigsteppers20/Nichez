package app.nichez.niche.service;

import app.nichez.common.NotFoundException;
import app.nichez.niche.dto.NicheStatsResponse;
import app.nichez.niche.repository.NicheRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Niche reach for G4, computed from real posts and cached per niche for {@link #CACHE_TTL}.
 *
 * <p>The numbers are the same for every viewer, so one cache entry serves everyone. Only niches
 * that exist are cached, so the map is bounded by the number of niches. The cache is in memory:
 * with several instances each keeps its own copy, which is fine for a 60 second figure.
 */
@Service
public class NicheStatsService {

    public static final String NICHE_NOT_FOUND = "NICHE_NOT_FOUND";
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record Cached(NicheStatsResponse stats, Instant expiresAt) {
    }

    private final NicheRepository niches;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    NicheStatsService(NicheRepository niches, Clock clock) {
        this.niches = niches;
        this.clock = clock;
    }

    public NicheStatsResponse stats(UUID nicheId) {
        Instant now = clock.instant();
        Cached cached = cache.get(nicheId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.stats();
        }
        if (!niches.exists(nicheId)) {
            throw new NotFoundException(NICHE_NOT_FOUND, "Niche not found.");
        }
        long postsToday = niches.countPublishedSince(nicheId, now.minus(FreshWindow.PERIOD));
        NicheStatsResponse stats = new NicheStatsResponse(postsToday, FreshWindow.forPostsInLastDay(postsToday));
        cache.put(nicheId, new Cached(stats, now.plus(CACHE_TTL)));
        return stats;
    }

    /**
     * Drops the cached figures for a niche whose posts changed. Inside a transaction this waits
     * for the commit, so a request racing the change cannot cache the old count again.
     */
    public void evict(UUID nicheId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(nicheId);
                }
            });
        } else {
            cache.remove(nicheId);
        }
    }
}
