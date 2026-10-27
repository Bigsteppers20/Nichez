package app.nichez.post.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over {@code drafts}. Every statement is scoped to one owner. */
@Repository
public class DraftRepository {

    /** A draft with the key of its media, if any. */
    public record Row(UUID id, UUID mediaId, String mediaKey, UUID nicheId, String caption, String audience,
            boolean allowComments, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    DraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int count(UUID owner) {
        return jdbc.sql("select count(*)::int from drafts where owner_id = :owner").param("owner", owner)
                .query(Integer.class).single();
    }

    public UUID insert(UUID owner, UUID mediaId, UUID nicheId, String caption, String audience, boolean allowComments) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into drafts (id, owner_id, media_id, niche_id, caption, audience, allow_comments)
                        values (:id, :owner, :media, :niche, :caption, :audience, :allow)""")
                .param("id", id)
                .param("owner", owner)
                .param("media", mediaId)
                .param("niche", nicheId)
                .param("caption", caption)
                .param("audience", audience)
                .param("allow", allowComments)
                .update();
        return id;
    }

    public List<Row> list(UUID owner, Instant afterTime, UUID afterId, int fetch) {
        return jdbc.sql("""
                        select d.id, d.media_id, coalesce(m.final_key, m.original_key) as media_key, d.niche_id,
                               d.caption, d.audience, d.allow_comments, d.updated_at
                        from drafts d left join media m on m.id = d.media_id
                        where d.owner_id = :owner
                          and (cast(:t as timestamptz) is null
                               or (d.updated_at, d.id) < (cast(:t as timestamptz), cast(:id as uuid)))
                        order by d.updated_at desc, d.id desc
                        limit :fetch""")
                .param("owner", owner)
                .param("t", afterTime == null ? null : Timestamp.from(afterTime))
                .param("id", afterId)
                .param("fetch", fetch)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("media_id", UUID.class),
                        rs.getString("media_key"), rs.getObject("niche_id", UUID.class), rs.getString("caption"),
                        rs.getString("audience"), rs.getBoolean("allow_comments"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public boolean delete(UUID owner, UUID id) {
        return jdbc.sql("delete from drafts where id = :id and owner_id = :owner")
                .param("id", id)
                .param("owner", owner)
                .update() > 0;
    }
}
