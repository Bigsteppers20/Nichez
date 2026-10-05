package app.nichez.auth.dto;

import java.time.Instant;

/** The signed-in session handed to the client. {@code expiresAt} is when the access token stops working (ISO-8601, UTC). */
public record SessionDto(String accessToken, String refreshToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "SessionDto[accessToken=<redacted>, refreshToken=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
