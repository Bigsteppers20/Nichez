package app.nichez.collection.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Bodies for saving posts and organising them into collections. */
public final class CollectionDtos {

    private CollectionDtos() {
    }

    /** Save into a collection too, if one is given. It must be the caller's own. */
    public record SaveRequest(UUID collectionId) {
    }

    /** After saving or unsaving: whether the post is saved now, and how many people saved it. */
    public record SaveState(@JsonProperty("saved") boolean saved, int saves) {
    }

    public record CollectionRequest(@NotBlank @Size(max = 40) String name) {
    }

    public record CollectionResponse(UUID id, String name, int posts, Instant createdAt) {
    }
}
