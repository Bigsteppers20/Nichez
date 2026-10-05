package app.nichez.auth.dto;

import java.util.UUID;

public record StartPhoneResponse(UUID challengeId, int resendAfterSeconds) {
}
