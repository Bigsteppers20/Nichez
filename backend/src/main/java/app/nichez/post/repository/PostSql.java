package app.nichez.post.repository;

/**
 * SQL fragments that decide which posts a person may be shown. Every query that lists posts
 * to a viewer uses these, so blocks, deactivated accounts, private communities and niche-only
 * audiences are applied the same way everywhere. They name the posts table {@code p} and take the
 * viewer as the bind parameter {@code :me}. All of them are constants: nothing from a request
 * is ever added to the text.
 */
public final class PostSql {

    private PostSql() {
    }

    /** True when neither person has blocked the other. Blocks are two-way (CLAUDE.md rule 11). */
    public static String notBlocked(String authorColumn) {
        return "not exists (select 1 from blocks b where (b.blocker_id = :me and b.blocked_id = " + authorColumn
                + ") or (b.blocker_id = " + authorColumn + " and b.blocked_id = :me))";
    }

    /** True when the account is neither deactivated nor waiting to be erased nor erased. */
    public static String accountActive(String userColumn) {
        return "not exists (select 1 from profiles ap where ap.id = " + userColumn
                + " and (ap.deactivated_at is not null or ap.deletion_requested_at is not null"
                + " or ap.deleted_at is not null))";
    }

    /** Published, in an audience the viewer belongs to, in a community they may see, and not blocked. */
    public static final String VISIBLE = """
            p.status = 'published'
            and (p.audience = 'everyone' or p.author_id = :me
                 or exists (select 1 from user_niches un where un.niche_id = p.niche_id and un.user_id = :me))
            and (p.community_id is null
                 or exists (select 1 from communities c where c.id = p.community_id and c.deleted_at is null
                            and (not c.is_private
                                 or exists (select 1 from community_members cm
                                            where cm.community_id = c.id and cm.user_id = :me))))
            and %s
            and %s
            """.formatted(notBlocked("p.author_id"), accountActive("p.author_id"));

    /**
     * On top of {@link #VISIBLE}, what the home feed leaves out for this viewer: muted authors,
     * posts marked "not interested", posts they reported (unless they turned that off), and sensitive
     * posts when they chose to hide them.
     */
    public static final String FEED_EXTRAS = """
            and not exists (select 1 from mutes mu where mu.muter_id = :me and mu.muted_id = p.author_id)
            and not exists (select 1 from post_not_interested ni where ni.user_id = :me and ni.post_id = p.id)
            and not exists (select 1 from reports rp
                            where rp.reporter_id = :me and rp.target_type = 'post' and rp.target_id = p.id
                              and coalesce((select cp.auto_hide_reported from content_preferences cp
                                            where cp.user_id = :me), true))
            and (not p.sensitive
                 or coalesce((select cp.sensitive_filter from content_preferences cp where cp.user_id = :me), 'veil')
                    <> 'hide')
            """;
}
