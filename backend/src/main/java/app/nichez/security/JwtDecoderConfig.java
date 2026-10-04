package app.nichez.security;

import app.nichez.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies Supabase access tokens according to {@link AuthProperties#mode()}.
 * Every token must have a valid signature, an unexpired {@code exp} (30 s clock
 * skew), the exact issuer, the audience {@value #AUDIENCE}, and a UUID subject.
 */
@Configuration(proxyBeanMethods = false)
class JwtDecoderConfig {

    static final String AUDIENCE = "authenticated";
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    JwtDecoder jwtDecoder(AuthProperties auth) {
        NimbusJwtDecoder decoder = switch (auth.mode()) {
            case HS256 -> NimbusJwtDecoder
                    .withSecretKey(new SecretKeySpec(
                            auth.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            // Supabase asymmetric signing keys are ES256 (ECC P-256) or RS256.
            case JWKS -> NimbusJwtDecoder
                    .withJwkSetUri(auth.jwksUri())
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        };
        decoder.setJwtValidator(validator(auth.issuer()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(issuer),
                JwtDecoderConfig::checkAudience,
                JwtDecoderConfig::checkSubject);
    }

    private static OAuth2TokenValidatorResult checkAudience(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience != null && audience.contains(AUDIENCE)) {
            return OAuth2TokenValidatorResult.success();
        }
        return failure("The aud claim is not valid");
    }

    private static OAuth2TokenValidatorResult checkSubject(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException ex) {
            return failure("The sub claim is not a user id");
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
