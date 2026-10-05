package app.nichez.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.auth.repository.OtpSendRepository;
import app.nichez.auth.repository.PhoneChallengeRepository;
import app.nichez.common.AuditEventRepository;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.repository.MediaPurgeRepository;
import app.nichez.post.repository.PostRepository;
import app.nichez.post.service.DeletedPostMediaPurgeJob;
import app.nichez.profile.repository.ProfileRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Runs the real filter chain with locally signed tokens. The database is left out
 * on purpose: nothing here touches it, so these tests need no Docker.
 */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER,
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTests {

    // JPA is excluded above, so the repositories that feature beans need are stubbed out.
    @MockitoBean
    PhoneChallengeRepository phoneChallengeRepository;
    @MockitoBean
    OtpSendRepository otpSendRepository;
    @MockitoBean
    AuditEventRepository auditEventRepository;
    @MockitoBean
    ProfileRepository profileRepository;
    @MockitoBean
    NicheRepository nicheRepository;
    @MockitoBean
    PostRepository postRepository;
    @MockitoBean
    MediaPurgeRepository mediaPurgeRepository;
    // Needs a transaction manager, which goes with the excluded DataSource.
    @MockitoBean
    DeletedPostMediaPurgeJob deletedPostMediaPurgeJob;

    @Autowired
    MockMvc mvc;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ResultActions getWith(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(token)));
    }

    // --- tokens -----------------------------------------------------------------

    @Test
    void validTokenPasses() throws Exception {
        TestTokens token = TestTokens.valid();
        getWith("/api/v1/test/me", token.build())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(token.subject()))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void tokenThatExpiredWithinTheClockSkewStillPasses() throws Exception {
        getWith("/api/v1/test/me", TestTokens.valid().expiresIn(Duration.ofSeconds(-10)).build())
                .andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejectedWithTokenExpired() throws Exception {
        getWith("/api/v1/test/me", TestTokens.valid().expiresIn(Duration.ofMinutes(-5)).build())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }

    @Test
    void wrongIssuerIsRejectedWithTokenInvalid() throws Exception {
        getWith("/api/v1/test/me", TestTokens.valid().issuer("https://evil.invalid/auth/v1").build())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void wrongAudienceIsRejectedWithTokenInvalid() throws Exception {
        getWith("/api/v1/test/me", TestTokens.valid().audience("anon").build())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void tamperedTokenIsRejectedWithTokenInvalid() throws Exception {
        String tampered = TestTokens.tampered(TestTokens.valid().build(), UUID.randomUUID().toString());
        getWith("/api/v1/test/me", tampered)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        getWith("/api/v1/test/me",
                TestTokens.valid().signedWith("another-secret-that-is-also-32-chars-long").build())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void subjectThatIsNotAUuidIsRejected() throws Exception {
        getWith("/api/v1/test/me", TestTokens.valid().subject("not-a-uuid").build())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        getWith("/api/v1/test/me", "not.a.jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void missingTokenIsRejectedWithAuthenticationRequired() throws Exception {
        mvc.perform(get("/api/v1/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void unauthorizedBodyCarriesRequestIdMatchingTheHeader() throws Exception {
        mvc.perform(get("/api/v1/test/me").header("X-Request-Id", "client-req-12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "client-req-12345"))
                .andExpect(jsonPath("$.requestId").value("client-req-12345"));
    }

    @Test
    void unsafeClientRequestIdIsReplaced() throws Exception {
        String id = mvc.perform(get("/api/v1/ping").header("X-Request-Id", "bad id\nwith newline"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(id).matches("[0-9a-f-]{36}");
    }

    // --- roles ----------------------------------------------------------------

    @Test
    void nonAdminGets403OnAdminRoute() throws Exception {
        getWith("/api/v1/admin/test", TestTokens.valid().build())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void adminRoleInUserMetadataIsIgnored() throws Exception {
        getWith("/api/v1/admin/test",
                TestTokens.valid().userMetadata(Map.of("role", "admin")).build())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void adminRoleInAppMetadataIsAccepted() throws Exception {
        getWith("/api/v1/admin/test",
                TestTokens.valid().appMetadata(Map.of("role", "admin")).build())
                .andExpect(status().isOk());
    }

    @Test
    void anonymousGets401OnAdminRoute() throws Exception {
        mvc.perform(get("/api/v1/admin/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // --- public and deny-by-default ----------------------------------------------

    @Test
    void pingIsPublicAndReportsStatusAndVersion() throws Exception {
        mvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.version").isNotEmpty());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsRequireAuthenticationBeforeAnythingElse() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathWithTokenIs404InProblemFormat() throws Exception {
        getWith("/api/v1/does-not-exist", TestTokens.valid().build())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // --- error handler ----------------------------------------------------------

    @Test
    void validationFailureIs400WithFieldErrorsAndNoRejectedValue() throws Exception {
        String token = TestTokens.valid().build();
        mvc.perform(post("/api/v1/test/validate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"far-too-long-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("far-too-long-value"));
    }

    @Test
    void malformedJsonIs400BadRequest() throws Exception {
        mvc.perform(post("/api/v1/test/validate")
                        .header("Authorization", bearer(TestTokens.valid().build()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void notFoundExceptionIs404WithItsOwnCode() throws Exception {
        getWith("/api/v1/test/not-found", TestTokens.valid().build())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    void conflictIs409() throws Exception {
        getWith("/api/v1/test/conflict", TestTokens.valid().build())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SAVED"));
    }

    @Test
    void idempotencyMismatchIs422() throws Exception {
        getWith("/api/v1/test/idempotency", TestTokens.valid().build())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISMATCH"));
    }

    @Test
    void rateLimitIs429WithRetryAfter() throws Exception {
        getWith("/api/v1/test/rate-limit", TestTokens.valid().build())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void unexpectedErrorIs500WithGenericMessageOnly() throws Exception {
        getWith("/api/v1/test/boom", TestTokens.valid().build())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-internal-detail")
                        .doesNotContain("jdbc:"));
    }

    @Test
    void methodNotAllowedUsesTheProblemFormat() throws Exception {
        mvc.perform(post("/api/v1/test/me").header("Authorization", bearer(TestTokens.valid().build())))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
