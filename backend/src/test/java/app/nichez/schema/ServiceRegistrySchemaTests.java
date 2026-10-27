package app.nichez.schema;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.TestPostgres;
import app.nichez.TestSql;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The service registry (api_services, service_tables): every API area from FEATURE_MANIFEST.md
 * section 3 is listed, and every table in public belongs to exactly one of them. A migration that
 * adds a table without adding its service_tables row fails here.
 */
class ServiceRegistrySchemaTests {

    private static final List<String> SERVICES = List.of("auth-session", "onboarding", "home-feed", "post-detail",
            "comments", "creating-posts", "live", "explore-search", "communities", "profiles-follows",
            "saved-collections", "notifications-push", "safety-moderation", "settings-account", "kyc",
            "wallet-payouts", "direct-messages", "app-config");

    private final JdbcTemplate jdbc = new JdbcTemplate(TestPostgres.dataSource());

    @Test
    void everyApiAreaOfTheManifestIsRegisteredInOrder() {
        assertThat(jdbc.queryForList(
                "select slug from api_services order by string_to_array(manifest_section, '.')::int[]", String.class))
                .containsExactlyElementsOf(SERVICES);
    }

    @Test
    void everyTableInPublicHasExactlyOneOwner() {
        List<String> unowned = jdbc.queryForList("""
                select t.table_name from information_schema.tables t
                where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                  and not exists (select 1 from service_tables s where s.table_name = t.table_name)
                order by 1""", String.class);
        assertThat(unowned).as("tables with no row in service_tables").isEmpty();
    }

    @Test
    void everyRegisteredTableExists() {
        List<String> missing = jdbc.queryForList("""
                select s.table_name from service_tables s
                where not exists (select 1 from information_schema.tables t
                                  where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                                    and t.table_name = s.table_name)
                order by 1""", String.class);
        assertThat(missing).as("service_tables rows naming a table that does not exist").isEmpty();
    }

    @Test
    void theBackendCanReadTheRegistryAndNothingElse() throws SQLException {
        assertThat(TestSql.asRole("api_service", "select * from api_services")).isNull();
        assertThat(TestSql.asRole("api_service", "select * from service_tables")).isNull();
        assertThat(TestSql.asRole("api_service", "insert into api_services (slug, name, manifest_section, priority) "
                + "values ('x', 'X', '9.9', 'P0')")).contains("permission denied");
        assertThat(TestSql.asRole("api_service", "delete from service_tables")).contains("permission denied");
        for (String role : List.of("anon", "authenticated")) {
            assertThat(TestSql.asRole(role, "select * from api_services")).as(role).contains("permission denied");
            assertThat(TestSql.asRole(role, "select * from service_tables")).as(role).contains("permission denied");
        }
    }
}
