package app.nichez.safety.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Placeholder until blocking exists (Flow 10): there is no blocks table yet, so nobody is blocked.
 * Flow 10 replaces this with a query over the blocks table; callers already go through
 * {@link BlockPolicy}, so they need no change.
 */
@Component
class NoBlocksPolicy implements BlockPolicy {

    @Override
    public Set<UUID> blockedEitherWay(UUID user, Collection<UUID> others) {
        return Set.of();
    }
}
