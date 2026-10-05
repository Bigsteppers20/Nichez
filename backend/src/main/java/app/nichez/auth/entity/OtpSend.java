package app.nichez.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One SMS requested, kept so sends can be counted per phone and per IP over a sliding hour. */
@Entity
@Table(name = "phone_otp_sends")
public class OtpSend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "phone_e164", nullable = false, updatable = false)
    private String phoneE164;

    @Column(name = "ip_address", nullable = false, updatable = false)
    private String ipAddress;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected OtpSend() {
    }

    public OtpSend(UUID challengeId, String phoneE164, String ipAddress, Instant sentAt) {
        this.challengeId = challengeId;
        this.phoneE164 = phoneE164;
        this.ipAddress = ipAddress;
        this.sentAt = sentAt;
    }
}
