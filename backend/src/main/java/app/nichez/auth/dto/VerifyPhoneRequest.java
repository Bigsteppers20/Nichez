package app.nichez.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record VerifyPhoneRequest(
        @NotNull UUID challengeId,
        @NotBlank @Pattern(regexp = "^[0-9]{4,10}$", message = "must be 4 to 10 digits") String code) {

    /** The code is a credential: keep it out of any accidental log of this object. */
    @Override
    public String toString() {
        return "VerifyPhoneRequest[challengeId=" + challengeId + ", code=<redacted>]";
    }
}
