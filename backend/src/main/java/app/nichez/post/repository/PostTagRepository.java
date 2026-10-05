package app.nichez.post.repository;

import app.nichez.post.entity.PostTag;
import app.nichez.post.entity.PostTagId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostTagRepository extends JpaRepository<PostTag, PostTagId> {

    List<PostTag> findByIdPostId(UUID postId);
}
