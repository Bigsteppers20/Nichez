package app.nichez.auth.service;

import app.nichez.common.RateLimitedException;
import app.nichez.config.SupabaseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Server-to-server calls to Supabase Auth (GoTrue). Supabase generates, sends and
 * checks the OTP; this class only relays. Phones and codes are never logged and
 * response bodies are never logged (they can echo the phone).
 */
@Component
public class SupabaseAuthClient {

    /** A session Supabase issued after a correct code. */
    public record VerifiedSession(String accessToken, String refreshToken, Instant expiresAt, boolean newUser) {

        @Override
        public String toString() {
            return "VerifiedSession[accessToken=<redacted>, refreshToken=<redacted>, expiresAt=" + expiresAt
                    + ", newUser=" + newUser + "]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthClient.class);

    /** Statuses GoTrue uses for "that code is wrong, expired or unknown". */
    private static final Set<Integer> CODE_REJECTED = Set.of(400, 401, 403, 404, 410, 422);
    private static final Duration PROVIDER_RATE_LIMIT_RETRY = Duration.ofSeconds(60);
    /** Both timestamps are set in the same request for a first sign-in, so they differ by milliseconds at most. */
    private static final Duration FIRST_SIGN_IN_TOLERANCE = Duration.ofSeconds(5);

    private final RestClient client;
    private final Clock clock;

    public SupabaseAuthClient(SupabaseProperties supabase, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.client = RestClient.builder()
                .baseUrl(supabase.url())
                .requestFactory(factory)
                .defaultHeader("apikey", supabase.anonKey())
                .build();
        this.clock = clock;
    }

    /** Asks Supabase to text a one-time code. Same call whether or not the phone has an account. */
    public void sendOtp(String phoneE164) {
        int status = call("otp", "/auth/v1/otp", Map.of("phone", phoneE164, "channel", "sms"), null);
        if (status == 429) {
            throw new RateLimitedException(PROVIDER_RATE_LIMIT_RETRY);
        }
        if (status < 200 || status >= 300) {
            log.warn("Supabase otp call failed, status={}", status);
            throw new AuthProviderException();
        }
    }

    /**
     * Checks a code. Returns the session on success, or null when Supabase rejected the code.
     * Throws {@link AuthProviderException} when Supabase could not give a usable answer: that
     * is not the user's mistake and must not count as a wrong guess.
     */
    public VerifiedSession verify(String phoneE164, String code) {
        JsonNode[] body = new JsonNode[1];
        int status = call("verify", "/auth/v1/verify",
                Map.of("type", "sms", "phone", phoneE164, "token", code), body);
        if (CODE_REJECTED.contains(status)) {
            return null;
        }
        if (status != 200 || body[0] == null) {
            log.warn("Supabase verify call failed, status={}", status);
            throw new AuthProviderException();
        }
        return toSession(body[0]);
    }

    /**
     * Ends the session at Supabase for the access token the caller used. Best effort: the app also
     * discards its tokens, so a failure here is logged (error type only) and not reported to the person.
     */
    public void logout(String accessToken) {
        try {
            client.post()
                    .uri("/auth/v1/logout")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Supabase logout call did not complete: {}", e.getClass().getSimpleName());
        }
    }

    private int call(String operation, String path, Map<String, String> payload, JsonNode[] bodyOut) {
        try {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        HttpStatusCode code = response.getStatusCode();
                        if (bodyOut != null && code.value() == 200) {
                            bodyOut[0] = response.bodyTo(JsonNode.class);
                        }
                        return code.value();
                    });
        } catch (RestClientException e) {
            // Class name only: the message can contain the URL or request details.
            log.warn("Supabase {} call did not complete: {}", operation, e.getClass().getSimpleName());
            throw new AuthProviderException();
        }
    }

    private VerifiedSession toSession(JsonNode body) {
        String access = text(body, "access_token");
        String refresh = text(body, "refresh_token");
        if (access == null || refresh == null) {
            log.warn("Supabase verify response had no session");
            throw new AuthProviderException();
        }
        Instant expiresAt;
        if (body.path("expires_at").canConvertToLong() && body.path("expires_at").asLong() > 0) {
            expiresAt = Instant.ofEpochSecond(body.path("expires_at").asLong());
        } else if (body.path("expires_in").canConvertToLong() && body.path("expires_in").asLong() > 0) {
            expiresAt = clock.instant().plusSeconds(body.path("expires_in").asLong());
        } else {
            log.warn("Supabase verify response had no expiry");
            throw new AuthProviderException();
        }
        return new VerifiedSession(access, refresh, expiresAt, isFirstSignIn(body.path("user")));
    }

    /**
     * Supabase creates the user when the first code is sent and stamps phone_confirmed_at
     * the first time a code is verified. A first sign-in therefore has phone_confirmed_at
     * equal to last_sign_in_at; a returning user has a much older phone_confirmed_at.
     */
    private static boolean isFirstSignIn(JsonNode user) {
        Instant confirmed = instant(user, "phone_confirmed_at");
        Instant lastSignIn = instant(user, "last_sign_in_at");
        if (confirmed == null || lastSignIn == null) {
            return false;
        }
        return Duration.between(confirmed, lastSignIn).abs().compareTo(FIRST_SIGN_IN_TOLERANCE) <= 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
