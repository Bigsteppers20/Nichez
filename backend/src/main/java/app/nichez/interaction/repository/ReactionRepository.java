package app.nichez.interaction.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code reactions}. The post's {@code reaction_count} is kept by a trigger. */
@Repository
public class ReactionRepository {

    private final JdbcClient jdbc;

    ReactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sets the caller's reaction, replacing any earlier one. */
    public void set(UUID postId, UUID userId, String kind) {
        jdbc.sql("""
                        insert into reactions (post_id, user_id, kind) values (:post, :user, :kind)
                        on conflict (post_id, user_id) do update set kind = excluded.kind
                        where reactions.kind <> excluded.kind""")
                .param("post", postId)
                .param("user", userId)
                .param("kind", kind)
                .update();
    }

    public void clear(UUID postId, UUID userId) {
        jdbc.sql("delete from reactions where post_id = :post and user_id = :user")
                .param("post", postId)
                .param("user", userId)
                .update();
    }

    public int count(UUID postId) {
        return jdbc.sql("select reaction_count from posts where id = :id")
                .param("id", postId)
                .query(Integer.class)
                .single();
    }
}
