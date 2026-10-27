package app.nichez.post.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Everything a post card shows, loaded for a page of post ids at once, with the viewer's own
 * reaction and save. It does not decide who may see a post: callers pass ids that already passed
 * {@link PostSql}.
 */
@Repository
public class PostCardRepository {

    /** One post as stored, with the viewer's flags. Keys are object keys, never URLs. */
    public record Row(
            UUID id, UUID authorId, UUID nicheId, String nicheLabel, UUID communityId, String caption,
            String audience, boolean allowComments, String status, String mediaKind, String finalKey,
            String originalKey, String posterKey, boolean hasAudio, boolean sensitive, String sensitiveReason,
            int reactions, int comments, int reshares, int shares, int saves, long views, Instant createdAt,
            String myReaction, boolean saved) {
    }

    private final JdbcClient jdbc;

    PostCardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rows for the ids that exist, in no particular order. */
    public List<Row> rows(UUID me, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select p.id, p.author_id, p.niche_id, n.label as niche_label, p.community_id, p.caption,
                               p.audience, p.allow_comments, p.status, m.kind as media_kind, m.final_key,
                               m.original_key, m.poster_key, m.has_audio, p.sensitive, p.sensitive_reason,
                               p.reaction_count, p.comment_count, p.reshare_count, p.share_count, p.save_count,
                               p.view_count, p.created_at, r.kind as my_reaction,
                               (sv.post_id is not null) as saved
                        from posts p
                        join media m on m.id = p.media_id
                        join niches n on n.id = p.niche_id
                        left join reactions r on r.post_id = p.id and r.user_id = :me
                        left join saves sv on sv.post_id = p.id and sv.user_id = :me
                        where p.id in (:ids)""")
                .param("me", me)
                .param("ids", ids)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getObject("niche_id", UUID.class), rs.getString("niche_label"),
                        rs.getObject("community_id", UUID.class), rs.getString("caption"),
                        rs.getString("audience"), rs.getBoolean("allow_comments"), rs.getString("status"),
                        rs.getString("media_kind"), rs.getString("final_key"), rs.getString("original_key"),
                        rs.getString("poster_key"), rs.getBoolean("has_audio"), rs.getBoolean("sensitive"),
                        rs.getString("sensitive_reason"), rs.getInt("reaction_count"), rs.getInt("comment_count"),
                        rs.getInt("reshare_count"), rs.getInt("share_count"), rs.getInt("save_count"),
                        rs.getLong("view_count"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("my_reaction"), rs.getBoolean("saved")))
                .list();
    }

    /** Per post, how many reactions of each kind. Kinds nobody used are left out. */
    public Map<UUID, Map<String, Integer>> reactionBreakdown(Collection<UUID> ids) {
        Map<UUID, Map<String, Integer>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("select post_id, kind, count(*)::int as n from reactions where post_id in (:ids) group by post_id, kind")
                .param("ids", ids)
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getObject("post_id", UUID.class), k -> new HashMap<>())
                            .put(rs.getString("kind"), rs.getInt("n"));
                    return null;
                })
                .list();
        return result;
    }
}
