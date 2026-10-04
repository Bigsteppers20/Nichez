package app.nichez.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Two S3 clients on purpose:
 * <ul>
 *   <li>{@link S3Client} talks to {@code storage.endpoint}, for the backend's own
 *       server-side calls (HEAD, DELETE).</li>
 *   <li>{@link S3Presigner} signs with {@code storage.publicEndpoint}. The host is
 *       part of the SigV4 signature, so a URL must be signed for the host the
 *       client will actually call.</li>
 * </ul>
 * Path-style addressing works on both MinIO and R2.
 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties storage) {
        return S3Client.builder()
                .endpointOverride(storage.endpointUri())
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .forcePathStyle(true)
                // R2 and MinIO do not support every default checksum the SDK adds.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties storage) {
        return S3Presigner.builder()
                .endpointOverride(storage.publicEndpointUri())
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties storage) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
    }
}
