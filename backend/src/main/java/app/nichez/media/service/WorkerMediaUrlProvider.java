package app.nichez.media.service;

import app.nichez.config.MediaUrlProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code media.url-provider=worker}: URLs on the Cloudflare playback Worker, which checks a token
 * before serving the object from R2.
 *
 * <p>The URL is {@code {workerBaseUrl}/{key}?exp={unix seconds}&sig={signature}} where the
 * signature is HMAC-SHA256 over {@code key + "\n" + exp} with {@code media.token-secret},
 * base64url without padding. The Worker must recompute it, compare in constant time, and refuse
 * the request once {@code exp} has passed.
 */
@Component
@ConditionalOnProperty(name = "media.url-provider", havingValue = "worker")
class WorkerMediaUrlProvider implements MediaUrlProvider {

    private static final String ALGORITHM = "HmacSHA256";

    private final MediaUrlProperties properties;
    private final Clock clock;
    private final SecretKeySpec secret;

    WorkerMediaUrlProvider(MediaUrlProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.secret = new SecretKeySpec(properties.tokenSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String urlFor(String key) {
        if (key == null) {
            return null;
        }
        long exp = clock.instant().plus(properties.ttl()).getEpochSecond();
        String base = properties.workerBaseUrl().replaceAll("/+$", "");
        return base + "/" + encodePath(key) + "?exp=" + exp + "&sig=" + sign(key + "\n" + exp);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secret);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String encodePath(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}
