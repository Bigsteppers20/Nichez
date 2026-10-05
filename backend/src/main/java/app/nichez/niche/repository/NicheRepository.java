package app.nichez.niche.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL over niches and their moderators. Not JPA: {@code niches.slug} is citext. */
@Repository
public class NicheRepository {

    private final JdbcClient jdbc;

    NicheRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID nicheId) {
        return jdbc.sql("select exists (select 1 from niches where id = :id)")
                .param("id", nicheId)
                .query(Boolean.class)
                .single();
    }

    /** The niche's display label, or empty if no niche has that id. */
    public Optional<String> labelOf(UUID nicheId) {
        return jdbc.sql("select label from niches where id = :id")
                .param("id", nicheId)
                .query(String.class)
                .optional();
    }

    public boolean isMember(UUID nicheId, UUID userId) {
        return jdbc.sql("select exists (select 1 from user_niches where niche_id = :niche and user_id = :user)")
                .param("niche", nicheId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    public boolean isModerator(UUID nicheId, UUID userId) {
        return jdbc.sql("select exists (select 1 from niche_moderators where niche_id = :niche and user_id = :user)")
                .param("niche", nicheId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    /** Published posts in the niche since {@code since}. Served by posts_niche_status_published_idx. */
    public long countPublishedSince(UUID nicheId, Instant since) {
        return jdbc.sql("""
                        select count(*) from posts
                        where niche_id = :niche and status = 'published' and published_at >= :since""")
                .param("niche", nicheId)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
    }
}
