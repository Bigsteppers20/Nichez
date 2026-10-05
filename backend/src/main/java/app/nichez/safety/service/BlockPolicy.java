package app.nichez.safety.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Who is blocked from whom. Blocks are two-way: if either user blocked the other, neither sees
 * or reaches the other. Every query that shows one user's content to another goes through this.
 */
public interface BlockPolicy {

    /** The subset of {@code others} that {@code user} has blocked or that has blocked {@code user}. */
    Set<UUID> blockedEitherWay(UUID user, Collection<UUID> others);

    default boolean isBlockedEitherWay(UUID user, UUID other) {
        return !blockedEitherWay(user, Set.of(other)).isEmpty();
    }
}
