package app.nichez.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of the append-only audit trail. Rows are inserted, never updated. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    AuditEvent(String eventType, String ipAddress, Map<String, Object> details, Instant createdAt) {
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.details = details;
        this.createdAt = createdAt;
    }

    public String getEventType() {
        return eventType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
