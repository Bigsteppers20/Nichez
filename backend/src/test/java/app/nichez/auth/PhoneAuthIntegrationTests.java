package app.nichez.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.auth.PhoneAuthIntegrationTests.TestClockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import app.nichez.TestPostgres;

/**
 * Phone sign-in end to end: real filter chain, real Postgres with the shipped migrations,
 * Supabase Auth replaced by WireMock, time driven by a {@link MutableClock}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(TestClockConfig.class)
class PhoneAuthIntegrationTests {

    @RegisterExtension
    static WireMockExtension supabase = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("supabase.url", supabase::baseUrl);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    private static final Instant START = Instant.parse("2026-10-04T10:00:00Z");

    private static final String CODE = "123456";
    private static final String WRONG = "000000";

    /** A new user in Supabase: both timestamps are "now". */
    private static final String NEW_PHONE = "+2348031234567";
    /** A returning user: phone confirmed long before this sign-in. */
    private static final String EXISTING_PHONE = "+2348039876543";
    private static final long EXPIRES_AT = 1_791_111_111L;

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        clock.set(START);
        jdbc.execute("truncate table phone_otp_sends, phone_challenges, audit_events restart identity cascade");
        supabase.resetAll();
        supabase.stubFor(post(urlEqualTo("/auth/v1/otp")).willReturn(aResponse().withStatus(200).withBody("{}")));
        // Any code is wrong unless a more specific stub below says otherwise.
        supabase.stubFor(post(urlEqualTo("/auth/v1/verify")).atPriority(10)
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
                        .withBody("{\"error_code\":\"otp_expired\",\"msg\":\"Token has expired or is invalid\"}")));
        stubCorrectCode(NEW_PHONE, "2026-10-04T10:00:00Z", "2026-10-04T10:00:00Z");
        stubCorrectCode(EXISTING_PHONE, "2026-01-15T08:30:00Z", "2026-10-04T10:00:00Z");
    }

    // --- happy path -----------------------------------------------------------------

    @Test
    void happyPathStartsThenVerifiesANewUser() throws Exception {
        String challengeId = startOk("+234", "803 123 4567", "10.0.0.1");

        supabase.verify(1, postRequestedFor(urlEqualTo("/auth/v1/otp"))
                .withHeader("apikey", equalTo("test-anon-key"))
                .withRequestBody(matchingJsonPath("$.phone", equalTo(NEW_PHONE)))
                .withRequestBody(matchingJsonPath("$.channel", equalTo("sms"))));
        assertThat(jdbc.queryForObject("select count(*) from phone_challenges", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from phone_otp_sends", Long.class)).isEqualTo(1);

        verify(challengeId, CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.accessToken").value("access-secret"))
                .andExpect(jsonPath("$.session.refreshToken").value("refresh-secret"))
                .andExpect(jsonPath("$.session.expiresAt").value(Instant.ofEpochSecond(EXPIRES_AT).toString()))
                .andExpect(jsonPath("$.isNewUser").value(true));

        supabase.verify(1, postRequestedFor(urlEqualTo("/auth/v1/verify"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("sms")))
                .withRequestBody(matchingJsonPath("$.phone", equalTo(NEW_PHONE)))
                .withRequestBody(matchingJsonPath("$.token", equalTo(CODE))));
        assertThat(jdbc.queryForObject("select verified_at is not null from phone_challenges", Boolean.class))
                .isTrue();
    }

    @Test
    void returningUserIsNotNew() throws Exception {
        String challengeId = startOk("+234", "803 987 6543", "10.0.0.1");
        verify(challengeId, CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(false));
    }

    @Test
    void aVerifiedChallengeCannotBeUsedAgain() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        verify(challengeId, CODE).andExpect(status().isOk());
        verify(challengeId, CODE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_FOUND"));
        resend(challengeId, "10.0.0.1").andExpect(status().isNotFound());
    }

    // --- wrong code, attempts, lockout ---------------------------------------------

    @Test
    void wrongCodeCountsDownThenLocks() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");

        verify(challengeId, WRONG).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_CODE"))
                .andExpect(jsonPath("$.attemptsLeft").value(2))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(challengeId, WRONG).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.attemptsLeft").value(1));
        verify(challengeId, WRONG).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.attemptsLeft").value(0));

        // Locked now: even the right code is refused, and Supabase is not asked.
        supabase.resetRequests();
        verify(challengeId, CODE).andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LOCKED"))
                .andExpect(header().string("Retry-After", "1800"));
        supabase.verify(0, postRequestedFor(urlEqualTo("/auth/v1/verify")));

        assertThat(jdbc.queryForObject("select locked_until from phone_challenges", java.sql.Timestamp.class)
                .toInstant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
    }

    @Test
    void lockoutIsAuditedWithAMaskedPhone() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.7");
        for (int i = 0; i < 3; i++) {
            verify(challengeId, WRONG).andExpect(status().isBadRequest());
        }
        // A fourth refused attempt must not write a second event.
        verify(challengeId, WRONG).andExpect(status().isLocked());

        List<Map<String, Object>> events = jdbc.queryForList(
                "select event_type, ip_address, details::text as details from audit_events");
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event.get("event_type")).isEqualTo("OTP_LOCKED");
        // The IP of the request that tripped the lock: the third wrong code, sent without a custom address.
        assertThat(event.get("ip_address")).isEqualTo("127.0.0.1");
        String details = (String) event.get("details");
        assertThat(details).contains(challengeId).contains("+*********4567")
                .doesNotContain(NEW_PHONE).doesNotContain("8031234567");
    }

    @Test
    void auditEventsCannotBeChanged() {
        jdbc.update("insert into audit_events (event_type, details, created_at) values ('TEST_EVENT', '{}', now())");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("update audit_events set event_type = 'OTHER'"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("delete from audit_events"));
    }

    @Test
    void phoneStaysLockedAcrossNewChallenges() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        for (int i = 0; i < 3; i++) {
            verify(challengeId, WRONG);
        }
        start("+234", "8031234567", "10.0.0.2")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "1800"));
        resend(challengeId, "10.0.0.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"));
        // Another phone is unaffected.
        startOk("+234", "8039876543", "10.0.0.2");
    }

    @Test
    void afterTheLockoutALapsedChallengeNeedsAResend() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        for (int i = 0; i < 3; i++) {
            verify(challengeId, WRONG);
        }
        clock.advance(Duration.ofMinutes(31));
        verify(challengeId, CODE).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        resend(challengeId, "10.0.0.1").andExpect(status().isOk());
        verify(challengeId, CODE).andExpect(status().isOk());
    }

    @Test
    void providerFailureWhileVerifyingDoesNotUseUpAnAttempt() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        supabase.stubFor(post(urlEqualTo("/auth/v1/verify")).atPriority(1)
                .willReturn(aResponse().withStatus(503)));

        verify(challengeId, CODE).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUTH_PROVIDER_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("select attempts from phone_challenges", Integer.class)).isZero();
    }

    // --- expiry, cooldown, resend ---------------------------------------------------

    @Test
    void codeExpiresFiveMinutesAfterItWasSent() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        verify(challengeId, WRONG).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_CODE"));

        clock.advance(Duration.ofSeconds(1));
        verify(challengeId, CODE).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    @Test
    void resendWaitsForTheCooldownThenSendsAndExtendsTheExpiry() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");

        resend(challengeId, "10.0.0.1").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "60"));

        clock.advance(Duration.ofSeconds(30));
        resend(challengeId, "10.0.0.1").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));

        clock.advance(Duration.ofSeconds(30));
        resend(challengeId, "10.0.0.1").andExpect(status().isOk())
                .andExpect(jsonPath("$.resendAfterSeconds").value(60));
        supabase.verify(2, postRequestedFor(urlEqualTo("/auth/v1/otp")));

        // Six minutes after /start but only four after the resend: still valid.
        clock.advance(Duration.ofMinutes(4));
        verify(challengeId, CODE).andExpect(status().isOk());
    }

    @Test
    void resendGivesTheUserFreshAttempts() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        verify(challengeId, WRONG).andExpect(jsonPath("$.attemptsLeft").value(2));
        verify(challengeId, WRONG).andExpect(jsonPath("$.attemptsLeft").value(1));
        clock.advance(Duration.ofSeconds(60));
        resend(challengeId, "10.0.0.1").andExpect(status().isOk());
        verify(challengeId, WRONG).andExpect(jsonPath("$.attemptsLeft").value(2));
    }

    // --- hourly limits ---------------------------------------------------------------

    @Test
    void sixthSendToOnePhoneInAnHourIsRefused() throws Exception {
        for (int i = 0; i < 5; i++) {
            startOk("+234", "8031234567", "10.0.0." + i);
        }
        start("+234", "8031234567", "10.0.0.9")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "3600"));

        // The hour slides: when the first send ages out, one more is allowed.
        clock.advance(Duration.ofHours(1));
        startOk("+234", "8031234567", "10.0.0.9");
    }

    @Test
    void resendCountsTowardsThePerPhoneLimit() throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(61));
            resend(challengeId, "10.0.0." + (i + 2)).andExpect(status().isOk());
        }
        clock.advance(Duration.ofSeconds(61));
        resend(challengeId, "10.0.0.8").andExpect(status().isTooManyRequests());
    }

    @Test
    void twentyFirstSendFromOneIpIsRefusedButAnotherIpIsFine() throws Exception {
        for (int i = 0; i < 20; i++) {
            startOk("+234", "803123%04d".formatted(i), "192.0.2.10");
        }
        start("+234", "803123%04d".formatted(20), "192.0.2.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "3600"));
        startOk("+234", "803123%04d".formatted(20), "192.0.2.11");
    }

    // --- input rules -----------------------------------------------------------------

    @Test
    void unsupportedCountryIsRefusedBeforeAnythingIsSent() throws Exception {
        start("+33", "6 12 34 56 78", "10.0.0.1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUNTRY_NOT_SUPPORTED"));

        supabase.verify(0, anyRequestedFor(urlEqualTo("/auth/v1/otp")));
        assertThat(jdbc.queryForObject("select count(*) from phone_challenges", Long.class)).isZero();
    }

    @Test
    void invalidPhoneIs400InvalidPhone() throws Exception {
        start("+234", "123", "10.0.0.1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        supabase.verify(0, anyRequestedFor(urlEqualTo("/auth/v1/otp")));
    }

    @Test
    void malformedBodiesFailValidation() throws Exception {
        postJson("/start", "{\"dialCode\":\"\",\"nationalNumber\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson("/verify", "{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson("/resend", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownChallengeIs404() throws Exception {
        verify(UUID.randomUUID().toString(), CODE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_FOUND"));
        resend(UUID.randomUUID().toString(), "10.0.0.1").andExpect(status().isNotFound());
    }

    // --- privacy ---------------------------------------------------------------------

    @Test
    void startAnswersTheSameForNewAndExistingNumbers() throws Exception {
        String forNew = start("+234", "8031234567", "10.0.0.1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String forExisting = start("+234", "8039876543", "10.0.0.1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Map<String, Object> a = JsonPath.read(forNew, "$");
        Map<String, Object> b = JsonPath.read(forExisting, "$");
        assertThat(a.keySet()).containsExactlyInAnyOrder("challengeId", "resendAfterSeconds");
        assertThat(b.keySet()).isEqualTo(a.keySet());
        assertThat(b.get("resendAfterSeconds")).isEqualTo(a.get("resendAfterSeconds"));
        assertThat(UUID.fromString((String) a.get("challengeId")))
                .isNotEqualTo(UUID.fromString((String) b.get("challengeId")));

        // No account lookup of any kind: the only Supabase calls are the two OTP sends.
        supabase.verify(2, postRequestedFor(urlEqualTo("/auth/v1/otp")));
        supabase.verify(0, anyRequestedFor(urlEqualTo("/auth/v1/verify")));
        assertThat(supabase.getAllServeEvents()).hasSize(2);
    }

    @Test
    void providerFailureOnStartIs502WithoutLeakingDetails() throws Exception {
        supabase.stubFor(post(urlEqualTo("/auth/v1/otp")).atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("secret-provider-detail " + NEW_PHONE)));

        start("+234", "8031234567", "10.0.0.1")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUTH_PROVIDER_UNAVAILABLE"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-provider-detail").doesNotContain(NEW_PHONE));
    }

    @Test
    void noPhoneNumberOrCodeReachesTheLogs(CapturedOutput output) throws Exception {
        String challengeId = startOk("+234", "8031234567", "10.0.0.1");
        verify(challengeId, WRONG);
        verify(challengeId, WRONG);
        verify(challengeId, WRONG);
        clock.advance(Duration.ofMinutes(31));
        resend(challengeId, "10.0.0.1");
        verify(challengeId, CODE);

        // Only the application's own log lines: MockMvc also prints whole requests when another test fails.
        String appLogs = output.getAll().lines()
                .filter(line -> line.contains(" --- [") && line.contains("nichez"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        assertThat(appLogs)
                .contains("+*********4567")                 // the masked form is what gets logged
                .doesNotContain(NEW_PHONE)
                .doesNotContain("8031234567")
                .doesNotContain("access-secret")
                .doesNotContain("refresh-secret")
                .doesNotContain("\"" + CODE + "\"")
                .doesNotContain("code=" + CODE)
                .doesNotContain("code=" + WRONG);
    }

    @Test
    void voiceCallFallbackIsNotImplemented() throws Exception {
        // Decision 12. Not a public route, so with no token it is refused outright.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/phone/call").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---------------------------------------------------------------------

    private void stubCorrectCode(String phone, String phoneConfirmedAt, String lastSignInAt) {
        supabase.stubFor(post(urlEqualTo("/auth/v1/verify")).atPriority(1)
                .withRequestBody(matchingJsonPath("$.type", equalTo("sms")))
                .withRequestBody(matchingJsonPath("$.phone", equalTo(phone)))
                .withRequestBody(matchingJsonPath("$.token", equalTo(CODE)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"access-secret","refresh_token":"refresh-secret",
                                 "token_type":"bearer","expires_in":3600,"expires_at":%d,
                                 "user":{"id":"%s","phone_confirmed_at":"%s","last_sign_in_at":"%s"}}
                                """.formatted(EXPIRES_AT, UUID.randomUUID(), phoneConfirmedAt, lastSignInAt))));
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/phone" + path)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions start(String dialCode, String nationalNumber, String ip) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/phone/start")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dialCode\":\"%s\",\"nationalNumber\":\"%s\"}".formatted(dialCode, nationalNumber)));
    }

    private String startOk(String dialCode, String nationalNumber, String ip) throws Exception {
        String body = start(dialCode, nationalNumber, ip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.challengeId");
    }

    private ResultActions verify(String challengeId, String code) throws Exception {
        return postJson("/verify", "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challengeId, code));
    }

    private ResultActions resend(String challengeId, String ip) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/phone/resend")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"%s\"}".formatted(challengeId)));
    }
}
