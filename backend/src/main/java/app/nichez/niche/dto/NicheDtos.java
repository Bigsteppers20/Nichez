package app.nichez.niche.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies for the niche taxonomy, memberships and creating a niche. */
public final class NicheDtos {

    private NicheDtos() {
    }

    /** One pickable niche: the app's {@code NicheOption}. */
    public record Option(UUID id, String label) {
    }

    /**
     * One taxonomy group: the app's {@code NicheGroup}. {@code strict} makes the group single-choice
     * (Religion): the client enforces it and so does the server.
     */
    public record Group(String name, boolean strict, List<Option> options) {
    }

    /** A niche the caller belongs to. */
    public record MyNiche(UUID id, String label, String slug) {
    }

    /** The caller's picks from the taxonomy (at least 3). Niches joined elsewhere are kept. */
    public record SetNichesRequest(@NotEmpty @Size(max = 40) List<@NotNull UUID> nicheIds) {
    }

    public record CreateNicheRequest(
            @NotNull @Size(min = 2, max = 30) @Pattern(regexp = "^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.-]*$",
                    message = "must be letters, numbers, spaces and & ' . -") String name) {
    }

    /** After joining or leaving a niche. */
    public record Membership(@JsonProperty("joined") boolean joined, int members) {
    }

    /** A niche just created. */
    public record CreatedNiche(UUID id, String label, String slug) {
    }
}
