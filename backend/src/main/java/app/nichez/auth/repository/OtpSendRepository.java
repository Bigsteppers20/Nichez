package app.nichez.auth.repository;

import app.nichez.auth.entity.OtpSend;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpSendRepository extends JpaRepository<OtpSend, Long> {

    @Query("select count(s) from OtpSend s where s.phoneE164 = :phone and s.sentAt > :since")
    long countByPhoneSince(@Param("phone") String phone, @Param("since") Instant since);

    @Query("select count(s) from OtpSend s where s.ipAddress = :ip and s.sentAt > :since")
    long countByIpSince(@Param("ip") String ip, @Param("since") Instant since);

    /** Oldest send still inside the window: when it ages out, one more send is allowed. */
    @Query("select min(s.sentAt) from OtpSend s where s.phoneE164 = :phone and s.sentAt > :since")
    Optional<Instant> findOldestByPhoneSince(@Param("phone") String phone, @Param("since") Instant since);

    @Query("select min(s.sentAt) from OtpSend s where s.ipAddress = :ip and s.sentAt > :since")
    Optional<Instant> findOldestByIpSince(@Param("ip") String ip, @Param("since") Instant since);
}
