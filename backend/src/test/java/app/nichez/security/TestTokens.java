package app.nichez.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds HS256 tokens that look like Supabase access tokens, signed locally. */
public final class TestTokens {

    public static final String SECRET = "test-only-jwt-secret-that-is-at-least-32-chars";
    public static final String ISSUER = "https://test.invalid/auth/v1";

    private String signingSecret = SECRET;
    private String subject = UUID.randomUUID().toString();
    private String issuer = ISSUER;
    private List<String> audience = List.of("authenticated");
    private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
    private Map<String, Object> appMetadata = Map.of("provider", "phone");
    private Map<String, Object> userMetadata = Map.of();

    public static TestTokens valid() {
        return new TestTokens();
    }

    public TestTokens subject(String value) {
        this.subject = value;
        return this;
    }

    TestTokens issuer(String value) {
        this.issuer = value;
        return this;
    }

    TestTokens audience(String... values) {
        this.audience = List.of(values);
        return this;
    }

    TestTokens expiresIn(Duration fromNow) {
        this.expiresAt = Instant.now().plus(fromNow);
        return this;
    }

    public TestTokens appMetadata(Map<String, Object> value) {
        this.appMetadata = value;
        return this;
    }

    public TestTokens userMetadata(Map<String, Object> value) {
        this.userMetadata = value;
        return this;
    }

    TestTokens signedWith(String secret) {
        this.signingSecret = secret;
        return this;
    }

    public String subject() {
        return subject;
    }

    public String build() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(expiresAt.minus(Duration.ofHours(1))))
                    .expirationTime(Date.from(expiresAt))
                    .claim("role", "authenticated")
                    .claim("app_metadata", appMetadata)
                    .claim("user_metadata", userMetadata)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(signingSecret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Keeps the original signature but swaps the subject in the payload, as an attacker would. */
    static String tampered(String token, String newSubject) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + newSubject + "\"");
        if (changed.equals(payload)) {
            throw new IllegalStateException("subject not found in payload");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + encoded + "." + parts[2];
    }
}
