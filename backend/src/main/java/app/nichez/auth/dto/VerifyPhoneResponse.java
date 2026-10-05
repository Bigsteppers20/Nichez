package app.nichez.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifyPhoneResponse(SessionDto session, @JsonProperty("isNewUser") boolean isNewUser) {
}
