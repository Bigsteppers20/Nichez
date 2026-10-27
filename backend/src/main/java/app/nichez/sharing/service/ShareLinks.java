package app.nichez.sharing.service;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Share-link codes: a post id as 22 URL-safe characters. A code is only a shorter way to write the id;
 * it grants nothing, because the public lookup still applies the same visibility rules as everything else.
 */
final class ShareLinks {

    private ShareLinks() {
    }

    static String encode(UUID id) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    static Optional<UUID> decode(String code) {
        if (code == null || code.length() != 22) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(code);
            if (raw.length != 16) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
