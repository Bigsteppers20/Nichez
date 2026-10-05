package app.nichez.post.repository;

import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The two list queries match the indexes on {@code posts}: (niche_id, status, published_at desc)
 * and (author_id, published_at desc). Callers pass a {@link Pageable} for the page size.
 */
public interface PostRepository extends JpaRepository<Post, UUID> {

    Optional<Post> findByIdAndStatus(UUID id, PostStatus status);

    /** Loads the post and row-locks it until the transaction ends, so two deletes cannot both act. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") UUID id);

    Optional<Post> findByMediaId(UUID mediaId);

    List<Post> findByNicheIdAndStatusOrderByPublishedAtDesc(UUID nicheId, PostStatus status, Pageable page);

    List<Post> findByAuthorIdAndStatusOrderByPublishedAtDesc(UUID authorId, PostStatus status, Pageable page);
}
