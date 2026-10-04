package app.nichez.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class StorageConfigTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, StorageConfig.class)
            .withPropertyValues(
                    "storage.endpoint=http://minio:9000",
                    "storage.public-endpoint=http://10.0.2.2:9000",
                    "storage.region=us-east-1",
                    "storage.access-key=test-access-key",
                    "storage.secret-key=test-secret-key",
                    "storage.bucket=media");

    @EnableConfigurationProperties(StorageProperties.class)
    static class Props {
    }

    @Test
    void presignedUrlsUseThePublicEndpoint() {
        runner.run(context -> {
            URL url = context.getBean(S3Presigner.class).presignGetObject(r -> r
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(g -> g.bucket("media").key("posts/p1.jpg"))).url();

            assertThat(url.getHost()).isEqualTo("10.0.2.2");
            assertThat(url.getPort()).isEqualTo(9000);
            assertThat(url.getPath()).isEqualTo("/media/posts/p1.jpg");
        });
    }

    @Test
    void serverSideClientUsesTheInternalEndpoint() {
        runner.run(context -> assertThat(context.getBean(S3Client.class)
                .serviceClientConfiguration().endpointOverride())
                .contains(URI.create("http://minio:9000")));
    }
}
