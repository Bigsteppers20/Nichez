package app.nichez.media;

import static org.assertj.core.api.Assertions.assertThat;

import app.nichez.media.entity.UploadType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UploadTypeTests {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] withAscii(int offset, String text, int length) {
        byte[] out = new byte[length];
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, out, offset, ascii.length);
        return out;
    }

    @Test
    void recognisesEachTypeFromItsFirstBytes() {
        assertThat(UploadType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xDB))).contains(UploadType.JPEG);
        assertThat(UploadType.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).contains(UploadType.PNG);
        assertThat(UploadType.sniff("GIF89a....".getBytes(StandardCharsets.US_ASCII))).contains(UploadType.GIF);
        assertThat(UploadType.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)))
                .contains(UploadType.WEBP);
        assertThat(UploadType.sniff(withAscii(4, "ftypisom", 16))).contains(UploadType.MP4);
        assertThat(UploadType.sniff(withAscii(4, "ftypmp42", 16))).contains(UploadType.MP4);
        assertThat(UploadType.sniff(withAscii(4, "ftypqt  ", 16))).contains(UploadType.QUICKTIME);
        assertThat(UploadType.sniff(withAscii(4, "moov", 16))).contains(UploadType.QUICKTIME);
    }

    @Test
    void refusesWhatItCannotRecognise() {
        assertThat(UploadType.sniff(new byte[0])).isEmpty();
        assertThat(UploadType.sniff(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(UploadType.sniff("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(UploadType.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(UploadType.sniff(withAscii(4, "ftyp", 10))).isEmpty();
    }

    @Test
    void parsesContentTypesIgnoringCaseAndParameters() {
        assertThat(UploadType.fromMimeType("Image/PNG")).contains(UploadType.PNG);
        assertThat(UploadType.fromMimeType(" video/mp4; codecs=avc1 ")).contains(UploadType.MP4);
        assertThat(UploadType.fromMimeType("image/svg+xml")).isEqualTo(Optional.empty());
        assertThat(UploadType.fromMimeType(null)).isEmpty();
    }
}
