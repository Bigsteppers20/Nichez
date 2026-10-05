package app.nichez.common;

import app.nichez.config.RateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Allows at most {@code limit} calls per key in any sliding {@code window}.
 * In memory: fine for one instance, and each extra instance adds its own allowance.
 * Turned off as a whole by {@code rate-limit.enabled=false}.
 */
@Component
public class SlidingWindowRateLimiter {

    private static final int PRUNE_ABOVE_KEYS = 10_000;

    private final Clock clock;
    private final boolean enabled;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    SlidingWindowRateLimiter(Clock clock, RateLimitProperties properties) {
        this.clock = clock;
        this.enabled = properties.enabled();
    }

    /** Counts one call for {@code key}. Throws {@link RateLimitedException} (429, Retry-After) when over the limit. */
    public void check(String key, int limit, Duration window) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        Instant since = now.minus(window);
        if (calls.size() > PRUNE_ABOVE_KEYS) {
            calls.entrySet().removeIf(e -> synchronizedIsStale(e.getValue(), since));
        }
        Deque<Instant> times = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
                times.removeFirst();
            }
            if (times.size() >= limit) {
                throw new RateLimitedException(Duration.between(now, times.peekFirst().plus(window)));
            }
            times.addLast(now);
        }
    }

    private static boolean synchronizedIsStale(Deque<Instant> times, Instant since) {
        synchronized (times) {
            return times.isEmpty() || !times.peekLast().isAfter(since);
        }
    }
}
