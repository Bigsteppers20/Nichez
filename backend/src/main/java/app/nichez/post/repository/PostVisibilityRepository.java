package app.nichez.post.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Asks the database whether one person may see one post, with the same rules ({@link PostSql}) the lists use. */
@Repository
public class PostVisibilityRepository {

    private final JdbcClient jdbc;

    PostVisibilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The post-detail rule: the author sees their own post while it is processing, published or
     * failed; anyone else only a published post they are allowed to see.
     */
    public boolean canRead(UUID me, UUID postId) {
        return jdbc.sql("""
                        select exists (select 1 from posts p where p.id = :id and (
                            (p.author_id = :me and p.status in ('processing', 'published', 'failed'))
                            or (%s)))""".formatted(PostSql.VISIBLE))
                .param("id", postId)
                .param("me", me)
                .query(Boolean.class)
                .single();
    }

    /** The rule for reacting, commenting, saving and sharing: a published post the person can see. */
    public boolean canInteract(UUID me, UUID postId) {
        return jdbc.sql("select exists (select 1 from posts p where p.id = :id and (%s))".formatted(PostSql.VISIBLE))
                .param("id", postId)
                .param("me", me)
                .query(Boolean.class)
                .single();
    }
}
