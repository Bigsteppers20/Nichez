package app.nichez;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Helpers for tests that talk to the test database directly. */
public final class TestSql {

    private TestSql() {
    }

    /**
     * Runs one statement as the given database role on its own connection and returns the error
     * message, or null if it succeeded. The search_path matches what the migrations give api_service,
     * so citext comparisons behave as they do in production.
     */
    public static String asRole(String role, String sql) throws SQLException {
        try (Connection c = TestPostgres.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path = public, extensions");
            s.execute("SET ROLE " + role);
            try {
                s.execute(sql);
                return null;
            } catch (SQLException e) {
                return e.getMessage();
            }
        }
    }
}
