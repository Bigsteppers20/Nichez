package app.nichez.media.dto;

import java.util.UUID;

/** Where to PUT the file, and the id to post it with. */
public record SignUploadResponse(String uploadUrl, UUID mediaId) {
}
