package app.nichez.media.service;

import app.nichez.config.MediaUrlProperties;
import app.nichez.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** {@code media.url-provider=presigned}: S3 presigned GET URLs straight to the bucket. */
@Component
@ConditionalOnProperty(name = "media.url-provider", havingValue = "presigned")
class PresignedMediaUrlProvider implements MediaUrlProvider {

    private final S3Presigner presigner;
    private final StorageProperties storage;
    private final MediaUrlProperties properties;

    PresignedMediaUrlProvider(S3Presigner presigner, StorageProperties storage, MediaUrlProperties properties) {
        this.presigner = presigner;
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String urlFor(String key) {
        if (key == null) {
            return null;
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(properties.ttl())
                        .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(key).build())
                        .build())
                .url()
                .toString();
    }
}
