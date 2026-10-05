package app.nichez.media.repository;

import app.nichez.media.entity.Media;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    /** A user's own media only: the owner is always taken from the token, never from the request. */
    Optional<Media> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** Like {@link #findByIdAndOwnerId}, row-locked until the transaction ends, so two posts cannot claim it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Media m where m.id = :id and m.ownerId = :owner")
    Optional<Media> findByIdAndOwnerIdForUpdate(@Param("id") UUID id, @Param("owner") UUID ownerId);

    /** The row that owns a stored object, for matching an upload-complete event to its media. */
    Optional<Media> findByOriginalKey(String originalKey);
}
