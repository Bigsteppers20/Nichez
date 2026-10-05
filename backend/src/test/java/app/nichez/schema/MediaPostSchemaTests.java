package app.nichez.schema;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.TestPostgres;
import app.nichez.TestSql;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * media, posts and post_tags as shipped in supabase/migrations: defaults, every CHECK and key,
 * the updated_at trigger, and exactly what each database role may do.
 */
class MediaPostSchemaTests {

    private final JdbcTemplate jdbc = new JdbcTemplate(TestPostgres.dataSource());

    private UUID author;
    private UUID otherUser;
    private UUID niche;

    @BeforeEach
    void fixtures() {
        jdbc.execute("truncate table post_tags, posts, media");
        author = profile();
        otherUser = profile();
        niche = jdbc.queryForObject("select id from niches where slug = 'islam'", UUID.class);
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id) values (?)", id);
        return id;
    }

    private UUID media() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into media (id, owner_id, kind) values (?, ?, 'image')", id, author);
        return id;
    }

    private UUID post() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into posts (id, author_id, niche_id, media_id) values (?, ?, ?, ?)",
                id, author, niche, media());
        return id;
    }

    private void assertRejected(String sql, Object... args) {
        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, args), sql);
    }

    // --- defaults ----------------------------------------------------------------------

    @Test
    void rowsGetTheirDefaults() {
        UUID postId = post();
        assertThat(jdbc.queryForMap("select status, created_at is not null as has_created, updated_at is not null "
                + "as has_updated from media")).containsEntry("status", "pending")
                .containsEntry("has_created", true).containsEntry("has_updated", true);
        assertThat(jdbc.queryForMap("select status, audience, allow_comments, sensitive, reaction_count, "
                + "comment_count, published_at, deleted_at, reshare_of, caption from posts where id = ?", postId))
                .containsEntry("status", "processing").containsEntry("audience", "everyone")
                .containsEntry("allow_comments", true).containsEntry("sensitive", false)
                .containsEntry("reaction_count", 0).containsEntry("comment_count", 0)
                .containsEntry("published_at", null).containsEntry("deleted_at", null)
                .containsEntry("reshare_of", null).containsEntry("caption", null);
    }

    // --- media checks ------------------------------------------------------------------

    @Test
    void mediaRejectsBadValues() {
        UUID id = UUID.randomUUID();
        String insert = "insert into media (id, owner_id, kind, %s) values (?, ?, ?, ?)";
        assertRejected("insert into media (id, owner_id, kind) values (?, ?, 'gif')", id, author);
        assertRejected("insert into media (id, owner_id, kind, status) values (?, ?, 'image', 'done')", id, author);
        assertRejected(insert.formatted("bytes"), id, author, "image", -1L);
        assertRejected(insert.formatted("width"), id, author, "image", 0);
        assertRejected(insert.formatted("height"), id, author, "image", -5);
        assertRejected(insert.formatted("duration_seconds"), id, author, "video", -1);
        assertRejected("insert into media (id, owner_id, kind) values (?, null, 'image')", id);
        assertRejected("insert into media (id, owner_id, kind) values (?, ?, 'image')", id, UUID.randomUUID());
    }

    @Test
    void mediaAcceptsEveryDocumentedStatusAndKind() {
        for (String status : List.of("pending", "uploaded", "processing", "ready", "failed")) {
            jdbc.update("insert into media (id, owner_id, kind, status) values (?, ?, 'video', ?)",
                    UUID.randomUUID(), author, status);
        }
        assertThat(jdbc.queryForObject("select count(*) from media", Long.class)).isEqualTo(5);
    }

    @Test
    void twoMediaRowsCannotClaimTheSameStoredObject() {
        jdbc.update("insert into media (id, owner_id, kind, original_key) values (?, ?, 'image', 'u/a/1')",
                UUID.randomUUID(), author);
        assertRejected("insert into media (id, owner_id, kind, original_key) values (?, ?, 'image', 'u/a/1')",
                UUID.randomUUID(), author);
        // Rows with no key yet do not collide with each other.
        jdbc.update("insert into media (id, owner_id, kind) values (?, ?, 'image')", UUID.randomUUID(), author);
        jdbc.update("insert into media (id, owner_id, kind) values (?, ?, 'image')", UUID.randomUUID(), author);
    }

    @Test
    void updatedAtIsStampedByTheDatabaseOnEveryUpdate() {
        UUID id = media();
        java.sql.Timestamp before = jdbc.queryForObject("select updated_at from media where id = ?",
                java.sql.Timestamp.class, id);
        jdbc.update("update media set status = 'uploaded' where id = ?", id);
        java.sql.Timestamp after = jdbc.queryForObject("select updated_at from media where id = ?",
                java.sql.Timestamp.class, id);
        assertThat(after).isAfter(before);
        // A caller cannot set it to something else: the trigger wins.
        jdbc.update("update media set updated_at = '2000-01-01', status = 'ready' where id = ?", id);
        assertThat(jdbc.queryForObject("select updated_at from media where id = ?", java.sql.Timestamp.class, id))
                .isAfter(after);
    }

    // --- post checks -------------------------------------------------------------------

    @Test
    void postRequiresAnExistingAuthorNicheAndMedia() {
        UUID mediaId = media();
        String insert = "insert into posts (id, author_id, niche_id, media_id) values (?, ?, ?, ?)";
        assertRejected(insert, UUID.randomUUID(), UUID.randomUUID(), niche, mediaId);   // no such author
        assertRejected(insert, UUID.randomUUID(), author, UUID.randomUUID(), mediaId);  // no such niche
        assertRejected(insert, UUID.randomUUID(), author, niche, UUID.randomUUID());    // no such media
        assertRejected("insert into posts (id, author_id, niche_id) values (?, ?, ?)", UUID.randomUUID(), author, niche);
    }

    @Test
    void oneMediaFileBacksOnlyOnePost() {
        UUID mediaId = media();
        jdbc.update("insert into posts (id, author_id, niche_id, media_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), author, niche, mediaId);
        assertRejected("insert into posts (id, author_id, niche_id, media_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), author, niche, mediaId);
    }

    @Test
    void captionIsCappedAt2200Characters() {
        jdbc.update("insert into posts (id, author_id, niche_id, media_id, caption) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), author, niche, media(), "x".repeat(2200));
        assertRejected("insert into posts (id, author_id, niche_id, media_id, caption) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), author, niche, media(), "x".repeat(2201));
    }

    @Test
    void audienceStatusAndCountsAreConstrained() {
        UUID id = post();
        assertRejected("update posts set audience = 'followers' where id = ?", id);
        assertRejected("update posts set status = 'hidden' where id = ?", id);
        assertRejected("update posts set reaction_count = -1 where id = ?", id);
        assertRejected("update posts set comment_count = -1 where id = ?", id);
        jdbc.update("update posts set audience = 'niche_members' where id = ?", id);
        for (String status : List.of("failed", "removed", "processing")) {
            jdbc.update("update posts set status = ? where id = ?", status, id);
        }
    }

    @Test
    void publishedNeedsATimeAndDeletedNeedsATime() {
        UUID id = post();
        assertRejected("update posts set status = 'published' where id = ?", id);
        assertRejected("update posts set status = 'deleted' where id = ?", id);
        jdbc.update("update posts set status = 'published', published_at = now() where id = ?", id);
        jdbc.update("update posts set status = 'deleted', deleted_at = now() where id = ?", id);
    }

    @Test
    void aPostCanReshareAnotherButNotItself() {
        UUID original = post();
        UUID reshare = post();
        jdbc.update("update posts set reshare_of = ? where id = ?", original, reshare);
        assertRejected("update posts set reshare_of = id where id = ?", reshare);
        assertRejected("update posts set reshare_of = ? where id = ?", UUID.randomUUID(), reshare);
    }

    // --- post_tags ---------------------------------------------------------------------

    @Test
    void aUserIsTaggedInAPostOnlyOnce_andTagsDieWithTheirPost() {
        UUID postId = post();
        jdbc.update("insert into post_tags (post_id, user_id) values (?, ?)", postId, otherUser);
        assertRejected("insert into post_tags (post_id, user_id) values (?, ?)", postId, otherUser);
        assertRejected("insert into post_tags (post_id, user_id) values (?, ?)", postId, UUID.randomUUID());
        jdbc.update("delete from post_tags where post_id = ?", postId);
        jdbc.update("insert into post_tags (post_id, user_id) values (?, ?)", postId, otherUser);
        jdbc.execute("truncate table post_tags");
        assertThat(jdbc.queryForObject("select count(*) from post_tags", Long.class)).isZero();
    }

    // --- indexes -----------------------------------------------------------------------

    @Test
    void theFeedIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select indexdef from pg_indexes where schemaname = 'public' and tablename = 'posts'", String.class);
        assertThat(indexes).anyMatch(d -> d.contains("(niche_id, status, published_at DESC)"));
        assertThat(indexes).anyMatch(d -> d.contains("(author_id, published_at DESC)"));
        assertThat(indexes).anyMatch(d -> d.contains("(status, published_at DESC)"));
    }

    // --- access ------------------------------------------------------------------------

    @Test
    void everyTableHasRowLevelSecurityEnabledAndForced() {
        for (String table : List.of("media", "posts", "post_tags")) {
            assertThat(jdbc.queryForObject(
                    "select relrowsecurity and relforcerowsecurity from pg_class where oid = ('public.' || ?)::regclass",
                    Boolean.class, table)).as(table).isTrue();
        }
    }

    @Test
    void theDataApiRolesCannotTouchAnything() throws SQLException {
        for (String role : List.of("anon", "authenticated")) {
            for (String table : List.of("media", "posts", "post_tags")) {
                assertThat(TestSql.asRole(role, "select * from " + table)).as(role + " select " + table)
                        .contains("permission denied");
                assertThat(TestSql.asRole(role, "delete from " + table)).as(role + " delete " + table)
                        .contains("permission denied");
            }
        }
    }

    @Test
    void apiServiceCanSelectInsertAndUpdateButNeverDelete() throws SQLException {
        UUID mediaId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        assertThat(TestSql.asRole("api_service", "insert into media (id, owner_id, kind) values ('" + mediaId
                + "', '" + author + "', 'image')")).isNull();
        assertThat(TestSql.asRole("api_service", "update media set status = 'uploaded' where id = '" + mediaId + "'"))
                .isNull();
        assertThat(TestSql.asRole("api_service", "insert into posts (id, author_id, niche_id, media_id) values ('"
                + postId + "', '" + author + "', '" + niche + "', '" + mediaId + "')")).isNull();
        assertThat(TestSql.asRole("api_service", "update posts set reaction_count = reaction_count + 1 where id = '"
                + postId + "'")).isNull();
        assertThat(TestSql.asRole("api_service", "insert into post_tags (post_id, user_id) values ('" + postId
                + "', '" + otherUser + "')")).isNull();
        for (String table : List.of("media", "posts", "post_tags")) {
            assertThat(TestSql.asRole("api_service", "select * from " + table)).as("select " + table).isNull();
            assertThat(TestSql.asRole("api_service", "delete from " + table)).as("delete " + table)
                    .contains("permission denied");
        }
        assertThat(TestSql.asRole("api_service", "update post_tags set user_id = user_id")).contains("permission denied");
        // The writes really happened.
        assertThat(jdbc.queryForObject("select reaction_count from posts where id = ?", Integer.class, postId))
                .isEqualTo(1);
    }

    // --- the reusable helpers ----------------------------------------------------------

    @Test
    void grantApiServiceIsRepeatableAndReplacesThePolicies() {
        jdbc.queryForList("select public.grant_api_service('public.media', ARRAY['select', 'insert', 'update'])");
        jdbc.queryForList("select public.grant_api_service('public.media', ARRAY['select', 'insert', 'update'])");
        assertThat(jdbc.queryForList("select policyname from pg_policies where tablename = 'media' "
                + "and roles = '{api_service}' order by policyname", String.class))
                .containsExactly("api_service_insert", "api_service_select", "api_service_update");
    }

    @Test
    void grantApiServiceRefusesAnUnknownCommand() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.queryForList("select public.grant_api_service('public.media', ARRAY['truncate'])"));
    }

    @Test
    void theHelpersAreNotCallableThroughTheDataApi() throws SQLException {
        for (String role : List.of("anon", "authenticated")) {
            assertThat(TestSql.asRole(role, "select public.grant_api_service('public.media', ARRAY['delete'])"))
                    .as(role).contains("permission denied");
        }
        assertThat(jdbc.queryForObject("select count(*) from pg_policies where tablename = 'media' "
                + "and policyname = 'api_service_delete'", Long.class)).isZero();
    }
}
