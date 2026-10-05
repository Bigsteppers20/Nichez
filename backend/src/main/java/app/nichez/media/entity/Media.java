package app.nichez.media.entity;

import app.nichez.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One uploaded file. It starts {@link MediaStatus#PENDING} when the client asks for an upload
 * URL, and the worker fills in the final key, size and dimensions once it has processed it.
 * The *Key fields are object keys in the media bucket, never public URLs.
 */
@Entity
@Table(name = "media")
public class Media extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Convert(converter = MediaKindConverter.class)
    @Column(nullable = false, updatable = false)
    private MediaKind kind;

    /** The content type the client claimed. Never trusted: the worker checks the real bytes. */
    @Column(name = "declared_type")
    private String declaredType;

    @Column(name = "original_key")
    private String originalKey;

    @Column(name = "final_key")
    private String finalKey;

    @Column(name = "poster_key")
    private String posterKey;

    private Long bytes;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Convert(converter = MediaStatusConverter.class)
    @Column(nullable = false)
    private MediaStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Stamped by the database trigger on every update; read back after each write. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Media() {
    }

    public Media(UUID id, UUID ownerId, MediaKind kind, String declaredType, String originalKey, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.kind = kind;
        this.declaredType = declaredType;
        this.originalKey = originalKey;
        this.status = MediaStatus.PENDING;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public MediaKind getKind() {
        return kind;
    }

    public String getDeclaredType() {
        return declaredType;
    }

    public String getOriginalKey() {
        return originalKey;
    }

    public String getFinalKey() {
        return finalKey;
    }

    public void setFinalKey(String finalKey) {
        this.finalKey = finalKey;
    }

    public String getPosterKey() {
        return posterKey;
    }

    public void setPosterKey(String posterKey) {
        this.posterKey = posterKey;
    }

    public Long getBytes() {
        return bytes;
    }

    public void setBytes(Long bytes) {
        this.bytes = bytes;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public void setStatus(MediaStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
