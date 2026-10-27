package app.nichez.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over saves, collections and the posts in them. Every statement is scoped to one owner. */
@Repository
public class CollectionRepository {

    /** A collection and how many posts it holds. */
    public record Row(UUID id, String name, int posts, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    CollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the post was saved by this call, false when it was already saved. */
    public boolean save(UUID userId, UUID postId, Instant now) {
        return jdbc.sql("""
                        insert into saves (user_id, post_id, created_at) values (:user, :post, :now)
                        on conflict do nothing""")
                .param("user", userId)
                .param("post", postId)
                .param("now", Timestamp.from(now))
                .update() > 0;
    }

    /** Unsaves the post and takes it out of all of this person's collections. */
    public void unsave(UUID userId, UUID postId) {
        jdbc.sql("""
                        delete from collection_posts cp using collections c
                        where c.id = cp.collection_id and c.owner_id = :user and cp.post_id = :post""")
                .param("user", userId)
                .param("post", postId)
                .update();
        jdbc.sql("delete from saves where user_id = :user and post_id = :post")
                .param("user", userId)
                .param("post", postId)
                .update();
    }

    public int saveCount(UUID postId) {
        return jdbc.sql("select save_count from posts where id = :id").param("id", postId).query(Integer.class).single();
    }

    public List<Row> list(UUID owner) {
        return jdbc.sql("""
                        select c.id, c.name, c.created_at,
                               (select count(*)::int from collection_posts cp where cp.collection_id = c.id) as posts
                        from collections c where c.owner_id = :owner order by c.created_at desc, c.id""")
                .param("owner", owner)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("posts"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<Row> find(UUID owner, UUID id) {
        return jdbc.sql("""
                        select c.id, c.name, c.created_at,
                               (select count(*)::int from collection_posts cp where cp.collection_id = c.id) as posts
                        from collections c where c.owner_id = :owner and c.id = :id""")
                .param("owner", owner)
                .param("id", id)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("posts"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** @throws DuplicateKeyException when the person already has a collection with that name (any letter case) */
    public UUID create(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into collections (id, owner_id, name) values (:id, :owner, :name)")
                .param("id", id)
                .param("owner", owner)
                .param("name", name)
                .update();
        return id;
    }

    /** @throws DuplicateKeyException when another of their collections already has that name */
    public boolean rename(UUID owner, UUID id, String name) {
        return jdbc.sql("update collections set name = :name where id = :id and owner_id = :owner")
                .param("id", id)
                .param("owner", owner)
                .param("name", name)
                .update() > 0;
    }

    public boolean delete(UUID owner, UUID id) {
        return jdbc.sql("delete from collections where id = :id and owner_id = :owner")
                .param("id", id)
                .param("owner", owner)
                .update() > 0;
    }

    public void addPost(UUID collectionId, UUID postId, Instant now) {
        jdbc.sql("""
                        insert into collection_posts (collection_id, post_id, added_at) values (:collection, :post, :now)
                        on conflict do nothing""")
                .param("collection", collectionId)
                .param("post", postId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void removePost(UUID collectionId, UUID postId) {
        jdbc.sql("delete from collection_posts where collection_id = :collection and post_id = :post")
                .param("collection", collectionId)
                .param("post", postId)
                .update();
    }
}
