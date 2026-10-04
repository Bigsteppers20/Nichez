package app.nichez.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Supabase access-token verification.
 * Env: AUTH_MODE, SUPABASE_JWT_SECRET, SUPABASE_JWKS_URI, SUPABASE_JWT_ISSUER.
 *
 * <p>HS256 verifies with the shared JWT secret (the local Supabase CLI default).
 * JWKS verifies against the project's published asymmetric keys (hosted projects).
 */
@Validated
@ConfigurationProperties("auth")
public record AuthProperties(
        @NotNull Mode mode,
        String jwtSecret,
        @Pattern(regexp = "^(https?://\\S+)?$", message = "must be an http(s) URL")
        String jwksUri,
        @NotBlank
        @Pattern(regexp = "^(https?://\\S+)?$", message = "must be an http(s) URL")
        String issuer) {

    public enum Mode { HS256, JWKS }

    static final int MIN_SECRET_LENGTH = 32;

    @AssertTrue(message = "auth.jwt-secret (SUPABASE_JWT_SECRET) is required, at least "
            + MIN_SECRET_LENGTH + " characters, when auth.mode=HS256")
    public boolean isJwtSecretSetForHs256() {
        return mode != Mode.HS256 || (jwtSecret != null && jwtSecret.length() >= MIN_SECRET_LENGTH);
    }

    @AssertTrue(message = "auth.jwks-uri (SUPABASE_JWKS_URI) is required when auth.mode=JWKS")
    public boolean isJwksUriSetForJwks() {
        return mode != Mode.JWKS || (jwksUri != null && !jwksUri.isBlank());
    }

    @Override
    public String toString() {
        return "AuthProperties[mode=" + mode + ", jwtSecret=<redacted>, jwksUri=<set>, issuer=<set>]";
    }
}
