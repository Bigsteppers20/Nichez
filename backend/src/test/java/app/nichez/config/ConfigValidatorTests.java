package app.nichez.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ConfigValidatorTests {

    private static Map<String, String> validConfig() {
        Map<String, String> p = new HashMap<>();
        p.put("db.url", "jdbc:postgresql://127.0.0.1:54322/postgres");
        p.put("db.username", "postgres");
        p.put("db.password", "postgres");
        p.put("auth.mode", "JWKS");
        p.put("auth.jwks-uri", "http://127.0.0.1:54321/auth/v1/.well-known/jwks.json");
        p.put("auth.issuer", "http://127.0.0.1:54321/auth/v1");
        p.put("storage.endpoint", "http://localhost:9000");
        p.put("storage.public-endpoint", "http://localhost:9000");
        p.put("storage.region", "us-east-1");
        p.put("storage.access-key", "k");
        p.put("storage.secret-key", "s");
        p.put("storage.bucket", "media");
        p.put("media.url-provider", "presigned");
        p.put("media.ttl", "15m");
        p.put("rate-limit.requests-per-minute", "120");
        p.put("rate-limit.burst", "30");
        return p;
    }

    private static List<String> validate(Map<String, String> props) {
        return ConfigValidator.validate(new Binder(new MapConfigurationPropertySource(props)));
    }

    @Test
    void validConfigurationHasNoProblems() {
        assertThat(validate(validConfig())).isEmpty();
    }

    @Test
    void startupFailsWhenAuthIssuerIsMissing() {
        Map<String, String> props = validConfig();
        props.remove("auth.issuer");

        assertThat(validate(props)).containsExactly("auth.issuer: must not be blank");
    }

    @Test
    void reportsEveryProblemAtOnceWithoutValues() {
        Map<String, String> props = validConfig();
        props.remove("db.password");
        props.remove("auth.issuer");
        props.put("auth.mode", "HS256");
        props.put("auth.jwt-secret", "short-secret-do-not-log");
        props.remove("storage.bucket");

        List<String> problems = validate(props);

        assertThat(problems).anyMatch(p -> p.startsWith("db.password"))
                .anyMatch(p -> p.startsWith("auth.issuer"))
                .anyMatch(p -> p.startsWith("auth.jwt-secret"))
                .anyMatch(p -> p.startsWith("storage.bucket"))
                .noneMatch(p -> p.contains("short-secret-do-not-log"));
    }

    @Test
    void workerFieldsAreNotRequiredWhenProviderIsPresigned() {
        Map<String, String> props = validConfig();
        props.remove("media.worker-base-url");
        props.remove("media.token-secret");

        assertThat(validate(props)).isEmpty();
    }

    @Test
    void workerFieldsAreRequiredWhenProviderIsWorker() {
        Map<String, String> props = validConfig();
        props.put("media.url-provider", "worker");

        assertThat(validate(props))
                .anyMatch(p -> p.startsWith("media.worker-base-url"))
                .anyMatch(p -> p.startsWith("media.token-secret"));
    }
}
