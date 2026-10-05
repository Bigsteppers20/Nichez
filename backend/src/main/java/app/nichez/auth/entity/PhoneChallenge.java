package app.nichez.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One phone sign-in attempt. Holds counters and timestamps only: Supabase Auth
 * owns the code itself. The phone is the full E.164 number because Supabase's
 * verify call needs it; it never appears in a response or an unmasked log line.
 */
@Entity
@Table(name = "phone_challenges")
public class PhoneChallenge {

    @Id
    private UUID id;

    @Column(name = "phone_e164", nullable = false, updatable = false)
    private String phoneE164;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Null on a new entity, which is how Spring Data knows to INSERT an entity with an assigned id. */
    @Version
    private Long version;

    protected PhoneChallenge() {
    }

    public PhoneChallenge(UUID id, String phoneE164, Instant now, Instant expiresAt) {
        this.id = id;
        this.phoneE164 = phoneE164;
        this.createdAt = now;
        this.lastSentAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** A new code was sent: the clock for expiry and cooldown restarts and the attempt counter starts over. */
    public void codeSent(Instant now, Instant newExpiry) {
        this.lastSentAt = now;
        this.expiresAt = newExpiry;
        this.attempts = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
}
