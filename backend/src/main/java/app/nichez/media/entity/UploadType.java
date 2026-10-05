package app.nichez.media.entity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Every content type an upload can have, with how to recognise it from the file's first bytes.
 * Which of these are accepted is config ({@code uploads.allowed-types}); a type that is not
 * listed here cannot be allowed, because its real bytes could not be checked.
 */
public enum UploadType {
    JPEG("image/jpeg", MediaKind.IMAGE),
    PNG("image/png", MediaKind.IMAGE),
    GIF("image/gif", MediaKind.IMAGE),
    WEBP("image/webp", MediaKind.IMAGE),
    MP4("video/mp4", MediaKind.VIDEO),
    QUICKTIME("video/quicktime", MediaKind.VIDEO);

    /** How many leading bytes {@link #sniff} needs to tell every type apart. */
    public static final int SNIFF_BYTES = 16;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final String mimeType;
    private final MediaKind kind;

    UploadType(String mimeType, MediaKind kind) {
        this.mimeType = mimeType;
        this.kind = kind;
    }

    public String mimeType() {
        return mimeType;
    }

    public MediaKind kind() {
        return kind;
    }

    /** The type for a content type such as {@code image/png; charset=x}. Case and parameters are ignored. */
    public static Optional<UploadType> fromMimeType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.mimeType.equals(bare)).findFirst();
    }

    /** What the file really is, judged by its first bytes, or empty when it is none of these. */
    public static Optional<UploadType> sniff(byte[] head) {
        if (startsWith(head, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, 0, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if (startsWith(head, 0, ascii("GIF87a")) || startsWith(head, 0, ascii("GIF89a"))) {
            return Optional.of(GIF);
        }
        if (startsWith(head, 0, ascii("RIFF")) && startsWith(head, 8, ascii("WEBP"))) {
            return Optional.of(WEBP);
        }
        // ISO base media (MP4 and QuickTime): a box size, then "ftyp", then the major brand.
        if (startsWith(head, 4, ascii("ftyp")) && head.length >= 12) {
            return Optional.of(startsWith(head, 8, ascii("qt  ")) ? QUICKTIME : MP4);
        }
        // Older QuickTime files open with a movie atom instead of ftyp.
        for (String atom : new String[] {"moov", "mdat", "wide", "free", "skip", "pnot"}) {
            if (startsWith(head, 4, ascii(atom))) {
                return Optional.of(QUICKTIME);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        return Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
