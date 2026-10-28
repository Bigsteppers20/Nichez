package app.nichez.common;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.TestSql;
import app.nichez.support.ApiTest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The request log kept in Supabase: what a call leaves behind in request_logs, what it must never
 * leave, who can read it, the retention purge and the hourly report. The writer's own failure handling
 * is in {@link RequestLogWriterTests}.
 */
@TestPropertySource(properties = {
        "request-log.enabled=true",
        "request-log.flush-interval=1h"   // the tests flush by hand
})
class RequestLogTests extends ApiTest {

    @Autowired
    RequestLogWriter writer;

    @BeforeEach
    void emptyLog() {
        writer.flush();
        jdbc.execute("truncate table request_logs");
    }

    private String requestIdOf(org.springframework.test.web.servlet.ResultActions result) {
        return result.andReturn().getResponse().getHeader("X-Request-Id");
    }

    private Map<String, Object> rowFor(String requestId) {
        writer.flush();
        return jdbc.queryForMap("select * from request_logs where request_id = ?", requestId);
    }

    @Test
    void aSignedInCallIsLoggedWithItsRouteStatusTimeAndCaller() throws Exception {
        User me = user();

        String id = requestIdOf(get(me, "/api/v1/me/settings"));

        Map<String, Object> row = rowFor(id);
        assertThat(row.get("method")).isEqualTo("GET");
        assertThat(row.get("route")).isEqualTo("/api/v1/me/settings");
        assertThat(row.get("path")).isNull();
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
        assertThat(((Number) row.get("duration_ms")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("user_id").toString()).isEqualTo(me.id().toString());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(START);
    }

    @Test
    void theRoutePatternIsLoggedNotTheRealPath() throws Exception {
        User me = user();

        String id = requestIdOf(get(me, "/api/v1/posts/00000000-0000-0000-0000-00000000abcd"));

        Map<String, Object> row = rowFor(id);
        assertThat(row.get("route")).isEqualTo("/api/v1/posts/{id}");
        assertThat(row.get("path")).isNull();
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(404);
    }

    @Test
    void aCallRefusedBeforeRoutingIsLoggedWithItsRealPathAndNoCaller() throws Exception {
        String id = requestIdOf(get(null, "/api/v1/me/settings"));

        Map<String, Object> row = rowFor(id);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(401);
        assertThat(row.get("route")).isEqualTo("(unmatched)");
        assertThat(row.get("path")).isEqualTo("/api/v1/me/settings");
        assertThat(row.get("user_id")).isNull();
    }

    @Test
    void aPublicCallIsLoggedWithoutACaller() throws Exception {
        String id = requestIdOf(get(null, "/api/v1/ping"));

        Map<String, Object> row = rowFor(id);
        assertThat(row.get("route")).isEqualTo("/api/v1/ping");
        assertThat(row.get("user_id")).isNull();
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
    }

    @Test
    void theQueryStringTheTokenAndTheBodyAreNeverStored() throws Exception {
        User me = user();

        get(me, "/api/v1/search?q=hush-hush-term&type=top");
        put(me, "/api/v1/me/push-token", body("token", "device-secret-token-123", "platform", "android"));
        writer.flush();

        String everything = jdbc.queryForObject("select coalesce(string_agg(r::text, ' '), '') from request_logs r",
                String.class);
        assertThat(everything).contains("/api/v1/search").contains("/api/v1/me/push-token")
                .doesNotContain("hush-hush-term")
                .doesNotContain("device-secret-token-123")
                .doesNotContain(me.token())
                .doesNotContain("Bearer");
    }

    @Test
    void callsOutsideTheApiAreNotLogged() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health"));
        writer.flush();

        assertThat(count("select count(*) from request_logs")).isZero();
    }

    @Test
    void rowsOlderThanTheRetentionArePurgedAndNewerOnesStay() {
        insertRow("old-row", START.minus(Duration.ofDays(31)), "/x", 200, 5);
        insertRow("new-row", START.minus(Duration.ofDays(29)), "/x", 200, 5);

        assertThat(writer.purge()).isEqualTo(1);

        assertThat(count("select count(*) from request_logs where request_id = 'old-row'")).isZero();
        assertThat(count("select count(*) from request_logs where request_id = 'new-row'")).isEqualTo(1);
    }

    @Test
    void theHourlyReportSummarisesRequestsErrorsAndResponseTimes() {
        insertRow("r1", START, "/api/v1/test-route", 200, 10);
        insertRow("r2", START, "/api/v1/test-route", 200, 20);
        insertRow("r3", START, "/api/v1/test-route", 404, 30);
        insertRow("r4", START, "/api/v1/test-route", 500, 1000);
        insertRow("r5", START, "/api/v1/other-route", 200, 7);

        Map<String, Object> row = jdbc.queryForMap(
                "select * from request_log_hourly where route = '/api/v1/test-route'");

        assertThat(((Number) row.get("requests")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("server_errors")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("client_errors")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("avg_ms")).intValue()).isEqualTo(265);
        assertThat(((Number) row.get("p50_ms")).intValue()).isEqualTo(25);
        assertThat(((Number) row.get("p95_ms")).intValue()).isBetween(850, 860);
        assertThat(((Number) row.get("max_ms")).intValue()).isEqualTo(1000);
    }

    @Test
    void onlyTheBackendCanReadOrWriteTheLog() throws SQLException {
        String insert = "insert into request_logs (created_at, request_id, method, route, status, duration_ms) "
                + "values (now(), 'x', 'GET', '/x', 200, 1)";
        for (String role : new String[] {"anon", "authenticated"}) {
            assertThat(TestSql.asRole(role, "select * from request_logs")).as(role).contains("permission denied");
            assertThat(TestSql.asRole(role, "select * from request_log_hourly")).as(role).contains("permission denied");
            assertThat(TestSql.asRole(role, insert)).as(role).contains("permission denied");
        }
        assertThat(TestSql.asRole("api_service", insert)).isNull();
        assertThat(TestSql.asRole("api_service", "select * from request_logs")).isNull();
        assertThat(TestSql.asRole("api_service", "select * from request_log_hourly")).isNull();
    }

    private void insertRow(String requestId, java.time.Instant at, String route, int status, int millis) {
        jdbc.update("""
                insert into request_logs (created_at, request_id, method, route, status, duration_ms)
                values (?, ?, 'GET', ?, ?, ?)""", Timestamp.from(at), requestId, route, status, millis);
    }
}
