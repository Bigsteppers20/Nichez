package app.nichez.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfigPropertiesValidationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class);

    @EnableConfigurationProperties({AuthProperties.class, MediaUrlProperties.class})
    static class Props {
    }

    private static FailureAnalysis analyze(Throwable failure) {
        FailureAnalysis analysis = new ConfigFailureAnalyzer().analyze(failure);
        assertThat(analysis).as("config failure analysis").isNotNull();
        return analysis;
    }

    // --- auth -----------------------------------------------------------------

    @Test
    void startupFailsWhenAuthIssuerIsMissing() {
        runner.withPropertyValues(
                        "auth.mode=JWKS",
                        "auth.jwks-uri=https://example.invalid/auth/v1/.well-known/jwks.json",
                        "media.url-provider=presigned", "media.ttl=15m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(analyze(context.getStartupFailure()).getDescription())
                            .contains("auth.issuer")
                            .contains("must not be blank");
                });
    }

    @Test
    void failureMessageNamesThePropertyButNeverPrintsItsValue() {
        String tooShortSecret = "short-secret-do-not-log";
        runner.withPropertyValues(
                        "auth.mode=HS256",
                        "auth.jwt-secret=" + tooShortSecret,
                        "auth.issuer=http://127.0.0.1:54321/auth/v1",
                        "media.url-provider=presigned", "media.ttl=15m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String description = analyze(context.getStartupFailure()).getDescription();
                    assertThat(description).contains("auth.jwt-secret").doesNotContain(tooShortSecret);
                });
    }

    @Test
    void validAuthConfigurationStarts() {
        runner.withPropertyValues(
                        "auth.mode=JWKS",
                        "auth.jwks-uri=https://example.invalid/auth/v1/.well-known/jwks.json",
                        "auth.issuer=https://example.invalid/auth/v1",
                        "media.url-provider=presigned", "media.ttl=15m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    // --- media ----------------------------------------------------------------

    @Test
    void workerFieldsAreNotRequiredWhenProviderIsPresigned() {
        runner.withPropertyValues(
                        "auth.mode=JWKS",
                        "auth.jwks-uri=https://example.invalid/auth/v1/.well-known/jwks.json",
                        "auth.issuer=https://example.invalid/auth/v1",
                        "media.url-provider=presigned",
                        "media.ttl=15m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaUrlProperties media = context.getBean(MediaUrlProperties.class);
                    assertThat(media.urlProvider()).isEqualTo(MediaUrlProperties.UrlProvider.PRESIGNED);
                    assertThat(media.workerBaseUrl()).isNull();
                    assertThat(media.tokenSecret()).isNull();
                });
    }

    @Test
    void workerFieldsAreRequiredWhenProviderIsWorker() {
        runner.withPropertyValues(
                        "auth.mode=JWKS",
                        "auth.jwks-uri=https://example.invalid/auth/v1/.well-known/jwks.json",
                        "auth.issuer=https://example.invalid/auth/v1",
                        "media.url-provider=worker",
                        "media.ttl=15m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(analyze(context.getStartupFailure()).getDescription())
                            .contains("media.worker-base-url")
                            .contains("media.token-secret");
                });
    }
}
