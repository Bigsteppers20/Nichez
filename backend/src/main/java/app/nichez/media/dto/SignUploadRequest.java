package app.nichez.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What the client is about to upload. Both values are claims: the type is checked against the
 * stored bytes when the post is created, and the size is signed into the upload URL.
 */
public record SignUploadRequest(
        @NotBlank @Size(max = 100) String contentType,
        @NotNull @Positive Long bytes) {
}
