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
        p.put("auth.sms.allowed-countries", "NG,US");
        p.put("auth.sms.max-sends-per-phone-per-hour", "5");
        p.put("auth.sms.max-sends-per-ip-per-hour", "20");
        p.put("auth.sms.resend-cooldown", "60s");
        p.put("auth.sms.code-ttl", "5m");
        p.put("auth.sms.max-attempts", "3");
        p.put("auth.sms.lockout", "30m");
        p.put("profile.handle-check-per-minute", "60");
        p.put("posts.media-retention", "30d");
        p.put("posts.purge-cron", "0 30 3 * * *");
        p.put("posts.purge-batch-size", "100");
        p.put("posts.create-per-hour", "20");
        p.put("uploads.allowed-types", "image/jpeg,video/mp4");
        p.put("uploads.max-image-size", "20MB");
        p.put("uploads.max-video-size", "200MB");
        p.put("uploads.url-ttl", "15m");
        p.put("uploads.sign-per-hour", "30");
        p.put("supabase.url", "http://127.0.0.1:54321");
        p.put("supabase.anon-key", "anon");
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
        p.put("app.public-base-url", "https://nichez.app");
        p.put("app.min-app-version", "1.0.0");
        p.put("app.terms-url", "https://nichez.app/terms");
        p.put("app.privacy-url", "https://nichez.app/privacy");
        p.put("social.view-min-watched-ms", "3000");
        p.put("social.view-batch-max", "50");
        p.put("social.view-batches-per-minute", "30");
        p.put("social.story-ttl", "24h");
        p.put("social.reactions-per-minute", "120");
        p.put("social.comments-per-hour", "60");
        p.put("social.follows-per-hour", "200");
        p.put("social.reports-per-hour", "20");
        p.put("social.searches-per-minute", "60");
        p.put("social.public-lookups-per-minute", "60");
        p.put("social.stories-per-day", "30");
        p.put("social.communities-per-day", "5");
        p.put("social.niches-per-day", "5");
        p.put("account.deletion-grace", "14d");
        p.put("account.deletion-cron", "0 0 4 * * *");
        p.put("account.deletion-batch-size", "50");
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
