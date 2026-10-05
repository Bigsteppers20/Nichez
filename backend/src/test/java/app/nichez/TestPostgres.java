package app.nichez;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres per test JVM, with the real {@code supabase/migrations} and {@code supabase/seed}
 * applied, so JPA's {@code ddl-auto=validate} and the tests run against the schema that ships.
 *
 * <p>Uses a Testcontainers {@code postgres:17-alpine} when Docker is running, and otherwise an
 * embedded Postgres, so database tests are never silently skipped on a machine without Docker.
 * Both are built from the same files in the same order. Surefire runs from {@code backend/},
 * hence {@code ../supabase}.
 */
public final class TestPostgres {

    private static final Path MIGRATIONS = Path.of("..", "supabase", "migrations");
    private static final Path SEEDS = Path.of("..", "supabase", "seed");
    private static final String ROLES_RESOURCE = "db/00_supabase_roles.sql";

    private static String jdbcUrl;
    private static String username;
    private static String password;

    private TestPostgres() {
    }

    /** Points {@code db.*} (what the app reads) at the test database. Call from a {@code @DynamicPropertySource}. */
    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("db.url", () -> jdbcUrl);
        registry.add("db.username", () -> username);
        registry.add("db.password", () -> password);
    }

    /** A fresh connection factory for tests that talk to the database directly, as the owner. */
    public static DataSource dataSource() {
        start();
        return new DriverManagerDataSource(jdbcUrl, username, password);
    }

    private static synchronized void start() {
        if (jdbcUrl != null) {
            return;
        }
        try {
            if (dockerAvailable()) {
                startContainer();
            } else {
                startEmbedded();
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not start the test database", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:17-alpine")
                .withCopyFileToContainer(MountableFile.forClasspathResource(ROLES_RESOURCE),
                        "/docker-entrypoint-initdb.d/00_supabase_roles.sql");
        int index = 1;
        for (Path file : sqlFiles(MIGRATIONS)) {
            container.withCopyFileToContainer(MountableFile.forHostPath(file),
                    "/docker-entrypoint-initdb.d/%02d_%s".formatted(index++, file.getFileName()));
        }
        for (Path file : sqlFiles(SEEDS)) {
            container.withCopyFileToContainer(MountableFile.forHostPath(file),
                    "/docker-entrypoint-initdb.d/%02d_seed_%s".formatted(index++, file.getFileName()));
        }
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        jdbcUrl = container.getJdbcUrl();
        username = container.getUsername();
        password = container.getPassword();
    }

    private static void startEmbedded() throws IOException, SQLException {
        // The first start unpacks the server binaries, which can take longer than the 10 s default on Windows.
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPGStartupWait(Duration.ofSeconds(90)).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }));
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(roles());
            for (Path file : sqlFiles(MIGRATIONS)) {
                statement.execute(Files.readString(file));
            }
            for (Path file : sqlFiles(SEEDS)) {
                statement.execute(Files.readString(file));
            }
        }
        jdbcUrl = postgres.getJdbcUrl("postgres", "postgres");
        username = "postgres";
        password = "test"; // embedded Postgres trusts local connections; the app's config just needs a value
    }

    private static String roles() throws IOException {
        try (InputStream in = TestPostgres.class.getClassLoader().getResourceAsStream(ROLES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(ROLES_RESOURCE + " is missing from the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Path> sqlFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + directory.toAbsolutePath(), e);
        }
    }
}
