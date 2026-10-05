package app.nichez.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Shape checks only. Whether this is a real, supported phone number is decided by the service. */
public record StartPhoneRequest(
        @NotBlank @Size(max = 8) String dialCode,
        @NotBlank @Size(max = 32) String nationalNumber) {
}
