package app.nichez.safety.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link BlockPolicy} over the {@code blocks} table. A block in either direction counts. */
@Component
class JdbcBlockPolicy implements BlockPolicy {

    private final JdbcClient jdbc;

    JdbcBlockPolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> blockedEitherWay(UUID user, Collection<UUID> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("""
                        select blocked_id from blocks where blocker_id = :me and blocked_id in (:others)
                        union
                        select blocker_id from blocks where blocked_id = :me and blocker_id in (:others)""")
                .param("me", user)
                .param("others", others)
                .query(UUID.class)
                .set();
    }
}
