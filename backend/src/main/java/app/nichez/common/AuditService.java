package app.nichez.common;

import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes security-relevant events to the append-only {@code audit_events} table.
 *
 * <p>Details must already be safe to store: no codes, tokens or secrets, and
 * phone numbers only through {@link Masking#phone}.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final Clock clock;

    AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Joins the caller's transaction when there is one, so an event is only
     * recorded if the change it describes is committed.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String eventType, String ipAddress, Map<String, Object> details) {
        events.save(new AuditEvent(eventType, ipAddress, Map.copyOf(details), clock.instant()));
    }
}
