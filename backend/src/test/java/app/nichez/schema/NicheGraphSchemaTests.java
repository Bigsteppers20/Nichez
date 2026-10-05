package app.nichez.schema;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.TestPostgres;
import app.nichez.TestSql;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The niche, follow and handle tables as shipped in supabase/migrations and supabase/seed:
 * the seed matches the app's taxonomy, and each role can do only what it should.
 */
class NicheGraphSchemaTests {

    private static final String USER_A = "00000000-0000-0000-0000-00000000000a";
    private static final String USER_B = "00000000-0000-0000-0000-00000000000b";
    private static final List<String> TABLES = List.of("profiles", "niche_groups", "niches", "user_niches",
            "niche_moderators", "follows", "reserved_handles");

    private DataSource owner() {
        return TestPostgres.dataSource();
    }

    @Test
    void seedMatchesTheAppTaxonomy() {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        assertThat(jdbc.queryForList("select name from niche_groups order by sort_order", String.class))
                .containsExactly("Religion", "Sport", "Work-life", "Technology");
        assertThat(jdbc.queryForList("select name from niche_groups where strict", String.class))
                .containsExactly("Religion");
        assertThat(jdbc.queryForList("""
                select n.slug from niches n join niche_groups g on g.id = n.group_id
                where g.name = 'Technology' order by n.sort_order""", String.class))
                .containsExactly("frontend", "backend", "devops", "qa", "product", "data-ml", "design");
        assertThat(jdbc.queryForObject("select count(*) from niches where is_system and group_id is not null",
                Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from niches where member_count <> 0", Long.class)).isZero();
    }

    @Test
    void reservedHandlesAreSeededAndMatchedIgnoringCase() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        assertThat(jdbc.queryForObject("select count(*) from reserved_handles", Long.class)).isPositive();
        try (Connection c = owner().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path = public, extensions");
            var rs = s.executeQuery("select count(*) from reserved_handles where handle = 'ADMIN'");
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void everyTableHasRowLevelSecurityEnabledAndForced() {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject(
                    "select relrowsecurity and relforcerowsecurity from pg_class where oid = ('public.' || ?)::regclass",
                    Boolean.class, table)).as(table).isTrue();
        }
    }

    @Test
    void theDataApiRolesCannotTouchAnything() throws SQLException {
        for (String role : List.of("anon", "authenticated")) {
            for (String table : TABLES) {
                assertThat(TestSql.asRole(role, "select * from " + table)).as(role + " on " + table)
                        .contains("permission denied");
            }
        }
    }

    @Test
    void apiServiceCanOnlyDeleteMembershipsAndFollows() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        jdbc.update("insert into auth.users (id) values (?::uuid), (?::uuid) on conflict do nothing", USER_A, USER_B);

        assertThat(TestSql.asRole("api_service", "insert into follows (follower_id, followee_id) values ('" + USER_A
                + "', '" + USER_B + "')")).isNull();
        assertThat(TestSql.asRole("api_service", "insert into user_niches (user_id, niche_id) "
                + "select '" + USER_A + "', id from niches where slug = 'islam'")).isNull();
        assertThat(TestSql.asRole("api_service", "delete from follows")).isNull();
        assertThat(TestSql.asRole("api_service", "delete from user_niches")).isNull();

        for (String table : List.of("niches", "niche_groups", "niche_moderators", "profiles", "reserved_handles")) {
            assertThat(TestSql.asRole("api_service", "delete from " + table)).as("delete from " + table)
                    .contains("permission denied");
        }
        assertThat(TestSql.asRole("api_service", "update follows set created_at = now()")).contains("permission denied");
        assertThat(TestSql.asRole("api_service", "update niches set member_count = member_count + 1 where slug = 'islam'"))
                .isNull();
        assertThat(TestSql.asRole("api_service", "insert into niche_groups (name) values ('nope')"))
                .contains("permission denied");
        jdbc.update("update niches set member_count = 0");   // leave the seed as shipped for the other tests
    }

    /** The exact statements ProfileRepository runs. The app's own tests connect as the owner, which skips RLS. */
    @Test
    void apiServiceCanRunTheHandleQueries() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        jdbc.update("insert into auth.users (id) values (?::uuid) on conflict do nothing", USER_A);
        jdbc.update("delete from profiles where id = ?::uuid", USER_A);

        List<String> statements = List.of(
                "select handle::text from reserved_handles",
                "select handle::text from profiles where verified and handle is not null and id <> '" + USER_B + "'",
                "select exists (select 1 from profiles where lower(handle::text) = lower('x') and id <> '" + USER_B + "')",
                "select n.slug::text from user_niches un join niches n on n.id = un.niche_id where un.user_id = '"
                        + USER_A + "' order by un.joined_at",
                "select slug::text from niches where is_system order by sort_order, slug",
                "insert into profiles (id, handle, created_at, updated_at) values ('" + USER_A + "', 'api_svc', now(), now()) "
                        + "on conflict (id) do update set handle = excluded.handle, updated_at = excluded.updated_at",
                "update profiles set onboarded_at = now(), updated_at = now() where id = '" + USER_A
                        + "' and onboarded_at is null and handle is not null "
                        + "and (select count(*) from user_niches where user_id = '" + USER_A + "') >= 3");
        for (String sql : statements) {
            assertThat(TestSql.asRole("api_service", sql)).as(sql).isNull();
        }
        jdbc.update("delete from profiles where id = ?::uuid", USER_A);
    }

    @Test
    void integrityRules() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(owner());
        jdbc.update("insert into auth.users (id) values (?::uuid) on conflict do nothing", USER_A);

        // Nobody follows themselves.
        assertThat(TestSql.asRole("api_service", "insert into follows (follower_id, followee_id) values ('" + USER_A
                + "', '" + USER_A + "')")).contains("follows_not_self");
        // Slugs are unique ignoring case.
        assertThat(TestSql.asRole("api_service", "insert into niches (label, slug) values ('Dup', 'FOOTBALL')"))
                .contains("niches_slug_key");
        // User-created niches have no group.
        assertThat(TestSql.asRole("api_service", "insert into niches (label, slug, created_by) values ('Excel Pain', "
                + "'excel-pain', '" + USER_A + "')")).isNull();
    }
}
