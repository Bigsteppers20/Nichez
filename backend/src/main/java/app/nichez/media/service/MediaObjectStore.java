package app.nichez.media.service;

import app.nichez.common.ApiException;
import app.nichez.config.StorageProperties;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The media bucket, as uploads need it: a presigned PUT for the client, and a look at what was
 * actually stored. Keys and bucket names never reach logs or error messages.
 */
@Service
public class MediaObjectStore {

    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(MediaObjectStore.class);

    /** What is stored under a key: its size and its first bytes (fewer when the file is shorter). */
    public record StoredObject(long bytes, byte[] head) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties storage;

    MediaObjectStore(S3Client s3, S3Presigner presigner, StorageProperties storage) {
        this.s3 = s3;
        this.presigner = presigner;
        this.storage = storage;
    }

    /**
     * A URL the client PUTs the file to. Content type and length are part of the signature, so the
     * upload must send exactly those headers: a different size or type is refused by storage.
     */
    public URL presignPut(String key, String contentType, long bytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(storage.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(bytes)
                .build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(put)
                        .build())
                .url();
    }

    /**
     * The stored object's size and first {@code headBytes} bytes, or empty if nothing has been
     * uploaded under the key. Throws 502 {@code STORAGE_UNAVAILABLE} when storage cannot answer.
     */
    public Optional<StoredObject> inspect(String key, int headBytes) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(storage.bucket()).key(key).build());
            long size = head.contentLength() == null ? 0 : head.contentLength();
            if (size == 0) {
                return Optional.of(new StoredObject(0, new byte[0]));
            }
            byte[] first = s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(storage.bucket()).key(key)
                            .range("bytes=0-" + (headBytes - 1))
                            .build())
                    .asByteArray();
            return Optional.of(new StoredObject(size, first));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable(e);
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    private static ApiException unavailable(SdkException e) {
        // The exception text can carry the bucket and key; log only its type.
        log.warn("Media storage call failed, error={}", e.getClass().getSimpleName());
        return new ApiException(HttpStatus.BAD_GATEWAY, STORAGE_UNAVAILABLE,
                "Media storage could not be reached. Try again.");
    }
}
