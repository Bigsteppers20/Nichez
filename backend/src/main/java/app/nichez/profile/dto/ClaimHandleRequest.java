package app.nichez.profile.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The size cap only bounds the work done on junk input; the real length rule (3 to 30) is HandleRules'. */
public record ClaimHandleRequest(@NotNull @Size(max = 100) String handle) {
}
