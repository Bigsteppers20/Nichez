package app.nichez.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.config.MediaUrlProperties;
import app.nichez.config.MediaUrlProperties.UrlProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The token format the playback Worker verifies. */
class WorkerMediaUrlProviderTests {

    private static final String SECRET = "a-playback-token-secret-of-32-chars-or-more";
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    private final WorkerMediaUrlProvider provider = new WorkerMediaUrlProvider(
            new MediaUrlProperties(UrlProvider.WORKER, Duration.ofMinutes(15), "https://play.example.com/", SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signsTheKeyAndExpiry() throws Exception {
        long exp = NOW.plus(Duration.ofMinutes(15)).getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(("media/a b.mp4\n" + exp).getBytes(StandardCharsets.UTF_8)));

        assertThat(provider.urlFor("media/a b.mp4"))
                .isEqualTo("https://play.example.com/media/a%20b.mp4?exp=" + exp + "&sig=" + sig);
    }

    @Test
    void noKeyMeansNoUrl() {
        assertThat(provider.urlFor(null)).isNull();
    }
}
