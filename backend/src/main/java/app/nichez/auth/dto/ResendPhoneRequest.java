package app.nichez.auth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ResendPhoneRequest(@NotNull UUID challengeId) {
}
