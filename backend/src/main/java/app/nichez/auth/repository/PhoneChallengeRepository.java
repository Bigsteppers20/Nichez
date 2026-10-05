package app.nichez.auth.repository;

import app.nichez.auth.entity.PhoneChallenge;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhoneChallengeRepository extends JpaRepository<PhoneChallenge, UUID> {

    /** Row lock so concurrent guesses on one challenge are counted one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PhoneChallenge c where c.id = :id")
    Optional<PhoneChallenge> findByIdForUpdate(@Param("id") UUID id);

    /** The latest lock still in force on this phone across all of its challenges, if any. */
    @Query("select max(c.lockedUntil) from PhoneChallenge c where c.phoneE164 = :phone and c.lockedUntil > :now")
    Optional<Instant> findActiveLockUntil(@Param("phone") String phone, @Param("now") Instant now);
}
