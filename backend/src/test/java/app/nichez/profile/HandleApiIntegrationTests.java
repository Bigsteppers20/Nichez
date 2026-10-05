package app.nichez.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.profile.HandleApiIntegrationTests.TestClockConfig;
import app.nichez.security.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /handles/check and PUT /me/handle against a real database with the shipped migrations
 * and seeds (reserved handles, system niches), real JWT verification and a movable clock.
 */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class HandleApiIntegrationTests {

    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock handleTestClock() {
            return new MutableClock(START);
        }
    }

    /** A signed-in user: a row in auth.users and a token whose subject is that id. */
    private record TestUser(UUID id, String token) {
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        clock.set(START);
        jdbc.execute("truncate table post_tags, posts, media, profiles, user_niches, follows, niche_moderators");
    }

    // --- helpers ---------------------------------------------------------------------

    private TestUser newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        return new TestUser(id, TestTokens.valid().subject(id.toString()).build());
    }

    private ResultActions check(TestUser user, String handle) throws Exception {
        return mvc.perform(get("/api/v1/handles/check").param("handle", handle)
                .header("Authorization", "Bearer " + user.token()));
    }

    private ResultActions claim(TestUser user, String handle) throws Exception {
        return mvc.perform(put("/api/v1/me/handle")
                .header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"%s\"}".formatted(handle)));
    }

    private void claimOk(TestUser user, String handle) throws Exception {
        claim(user, handle).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(handle));
    }

    private List<String> suggestionsFor(TestUser user, String handle) throws Exception {
        String body = check(user, handle).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.suggestions");
    }

    private void joinNiches(TestUser user, int count) {
        jdbc.update("""
                insert into user_niches (user_id, niche_id)
                select ?, id from niches where is_system order by sort_order, slug limit ?""", user.id(), count);
    }

    private String handleOf(TestUser user) {
        return jdbc.queryForObject("select handle::text from profiles where id = ?", String.class, user.id());
    }

    private Instant onboardedAt(TestUser user) {
        Timestamp at = jdbc.queryForObject("select onboarded_at from profiles where id = ?", Timestamp.class,
                user.id());
        return at == null ? null : at.toInstant();
    }

    // --- access ----------------------------------------------------------------------

    @Test
    void bothEndpointsRequireASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/handles/check").param("handle", "someone")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/handle").contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"someone\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- GET /handles/check ----------------------------------------------------------

    @Test
    void aFreeHandleIsAvailableWithNoSuggestions() throws Exception {
        check(newUser(), "fresh_handle")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.suggestions").isEmpty());
    }

    @Test
    void aTakenHandleIsUnavailableWithThreeFreeSuggestions() throws Exception {
        TestUser owner = newUser();
        claimOk(owner, "pivotmaster");
        TestUser other = newUser();

        List<String> suggestions = suggestionsFor(other, "pivotmaster");

        assertThat(suggestions).hasSize(3).doesNotHaveDuplicates();
        for (String suggestion : suggestions) {
            assertThat(suggestion).matches("[a-zA-Z0-9_.]{3,30}").isNotEqualToIgnoringCase("pivotmaster");
            assertThat(suggestion).startsWith("pivotmaster");
            // Each one really is free: claiming it works.
            assertThat(jdbc.queryForObject("select count(*) from profiles where lower(handle::text) = ?",
                    Long.class, suggestion.toLowerCase())).isZero();
        }
    }

    @Test
    void suggestionsUseTheUsersOwnNicheWordsAndDigits() throws Exception {
        claimOk(newUser(), "pivotmaster");
        TestUser me = newUser();
        joinNiches(me, 3);
        List<String> niches = jdbc.queryForList("""
                select replace(n.slug::text, '-', '') from user_niches un join niches n on n.id = un.niche_id
                where un.user_id = ?""", String.class, me.id());

        List<String> suggestions = suggestionsFor(me, "pivotmaster");

        assertThat(suggestions).anyMatch(s -> niches.stream().anyMatch(s::contains));
        assertThat(suggestions).anyMatch(s -> s.matches("pivotmaster\\d{2,3}"));
    }

    @Test
    void handlesCollideRegardlessOfLetterCase() throws Exception {
        TestUser owner = newUser();
        claimOk(owner, "PivotMaster");
        TestUser other = newUser();

        check(other, "pivotmaster").andExpect(jsonPath("$.available").value(false));
        check(other, "PIVOTMASTER").andExpect(jsonPath("$.available").value(false));
        // Their own handle is not "taken" from their point of view, whatever case they type.
        check(owner, "pivotmaster").andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void reservedAndLookalikeHandlesAreUnavailableWithSuggestions() throws Exception {
        TestUser user = newUser();
        for (String handle : List.of("admin", "ADMIN", "ad.min", "adrnin", "5upport", "Nichez")) {
            assertThat(suggestionsFor(user, handle)).as(handle).hasSize(3);
        }
    }

    @Test
    void handlesThatBreakTheFormatAreUnavailableNotAnError() throws Exception {
        TestUser user = newUser();
        for (String handle : List.of("ab", "has space", "dash-ed", "x".repeat(31), "___", "")) {
            check(user, handle).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.suggestions").isArray());
        }
        // The suggestions for rubbish are still usable handles.
        assertThat(suggestionsFor(user, "!!")).allMatch(s -> s.matches("[a-zA-Z0-9_.]{3,30}"));
    }

    @Test
    void aMissingHandleParameterIs400() throws Exception {
        mvc.perform(get("/api/v1/handles/check").header("Authorization", "Bearer " + newUser().token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void aVerifiedUsersHandleBlocksItsLookalikeButNotItself() throws Exception {
        TestUser verified = newUser();
        claimOk(verified, "pivotmaster");
        jdbc.update("update profiles set verified = true where id = ?", verified.id());
        TestUser other = newUser();

        check(other, "pivotrnaster").andExpect(jsonPath("$.available").value(false));
        check(other, "pivot.master").andExpect(jsonPath("$.available").value(false));
        check(verified, "PivotMaster").andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void checkIsLimitedToSixtyPerMinutePerUser() throws Exception {
        TestUser busy = newUser();
        for (int i = 0; i < 60; i++) {
            check(busy, "handle" + i).andExpect(status().isOk());
        }
        check(busy, "handle60").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "60"));

        // Another user has their own allowance.
        check(newUser(), "handle60").andExpect(status().isOk());

        // The window slides: a minute later the first user can check again.
        clock.advance(Duration.ofSeconds(60));
        check(busy, "handle60").andExpect(status().isOk());
    }

    // --- PUT /me/handle --------------------------------------------------------------

    @Test
    void claimingAHandleCreatesTheProfileAndReturnsIt() throws Exception {
        TestUser user = newUser();
        claimOk(user, "Mixed.Case_9");
        assertThat(handleOf(user)).isEqualTo("Mixed.Case_9");   // stored as typed
        assertThat(onboardedAt(user)).isNull();                  // no niches yet
    }

    @Test
    void aUserCanChangeTheirHandleAndReclaimTheirOwn() throws Exception {
        TestUser user = newUser();
        claimOk(user, "first_one");
        claimOk(user, "second_one");
        claimOk(user, "SECOND_ONE");   // same handle, new case
        assertThat(handleOf(user)).isEqualTo("SECOND_ONE");
        assertThat(jdbc.queryForObject("select count(*) from profiles", Long.class)).isEqualTo(1);
        // The old one is free again.
        claimOk(newUser(), "first_one");
    }

    @Test
    void formatBreakersAre400HandleInvalid() throws Exception {
        TestUser user = newUser();
        for (String handle : List.of("ab", "x".repeat(31), "has space", "dash-ed", "émile", "___")) {
            claim(user, handle).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("HANDLE_INVALID"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        assertThat(jdbc.queryForObject("select count(*) from profiles", Long.class)).isZero();
    }

    @Test
    void reservedHandlesAndLookalikesAre400HandleReserved() throws Exception {
        TestUser user = newUser();
        for (String handle : List.of("admin", "Admin", "NICHEZ", "ad.min", "a_d_m_i_n", "adrnin", "he1p", "r00t")) {
            claim(user, handle).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("HANDLE_RESERVED"));
        }
        assertThat(jdbc.queryForObject("select count(*) from profiles", Long.class)).isZero();
    }

    @Test
    void lookalikesOfVerifiedHandlesAreRefusedButTheVerifiedUserKeepsTheirs() throws Exception {
        TestUser verified = newUser();
        claimOk(verified, "pivotmaster");
        jdbc.update("update profiles set verified = true where id = ?", verified.id());

        claim(newUser(), "pivotrnaster").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HANDLE_RESERVED"));
        claim(newUser(), "pivot_master").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HANDLE_RESERVED"));
        claimOk(verified, "PivotMaster");
    }

    @Test
    void aHandleHeldByAnotherUserIs409InAnyCase() throws Exception {
        claimOk(newUser(), "Taken_One");
        TestUser other = newUser();
        for (String handle : List.of("Taken_One", "taken_one", "TAKEN_ONE")) {
            claim(other, handle).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HANDLE_TAKEN"));
        }
        assertThat(jdbc.queryForObject("select count(*) from profiles where lower(handle::text) = 'taken_one'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void aMissingHandleIsAValidationError() throws Exception {
        mvc.perform(put("/api/v1/me/handle").header("Authorization", "Bearer " + newUser().token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theCallerIsAlwaysTheTokenSubjectNeverTheBody() throws Exception {
        TestUser user = newUser();
        TestUser victim = newUser();
        mvc.perform(put("/api/v1/me/handle").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handle\":\"mine_only\",\"id\":\"%s\",\"userId\":\"%s\"}"
                                .formatted(victim.id(), victim.id())))
                .andExpect(status().isOk());
        assertThat(handleOf(user)).isEqualTo("mine_only");
        assertThat(jdbc.queryForObject("select count(*) from profiles where id = ?", Long.class, victim.id()))
                .isZero();
    }

    // --- onboarded_at ----------------------------------------------------------------

    @Test
    void onboardedAtIsSetWhenTheHandleCompletesThreeNiches() throws Exception {
        TestUser user = newUser();
        joinNiches(user, 3);
        claimOk(user, "all_set");
        assertThat(onboardedAt(user)).isEqualTo(START);
    }

    @Test
    void twoNichesAreNotEnough() throws Exception {
        TestUser user = newUser();
        joinNiches(user, 2);
        claimOk(user, "almost_there");
        assertThat(onboardedAt(user)).isNull();
    }

    @Test
    void anAlreadyChosenHandleIsPickedUpOnceTheThirdNicheExists() throws Exception {
        TestUser user = newUser();
        claimOk(user, "handle_first");
        assertThat(onboardedAt(user)).isNull();

        joinNiches(user, 3);
        clock.advance(Duration.ofMinutes(5));
        claimOk(user, "handle_first");
        assertThat(onboardedAt(user)).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void onboardedAtIsSetOnceAndNeverMoved() throws Exception {
        TestUser user = newUser();
        joinNiches(user, 3);
        claimOk(user, "stays_put");
        clock.advance(Duration.ofDays(1));
        claimOk(user, "stays_put_too");
        assertThat(onboardedAt(user)).isEqualTo(START);
    }

    @Test
    void aRejectedClaimDoesNotOnboardAnyone() throws Exception {
        TestUser user = newUser();
        joinNiches(user, 3);
        claim(user, "admin").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from profiles", Long.class)).isZero();
    }

    // --- concurrency -----------------------------------------------------------------

    @Test
    void whenManyUsersClaimTheSameHandleAtOnceExactlyOneWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            String base = "race" + round;
            // Different letter cases of one handle, so the case-insensitive unique index is what decides.
            List<String> variants = List.of(base, base.toUpperCase(), base, base.toUpperCase(), base, base,
                    base.toUpperCase(), base);
            List<TestUser> users = new ArrayList<>();
            variants.forEach(v -> users.add(newUser()));

            ExecutorService pool = Executors.newFixedThreadPool(variants.size());
            CountDownLatch ready = new CountDownLatch(variants.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                TestUser user = users.get(i);
                String handle = variants.get(i);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return claim(user, handle).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int ok = 0;
            int taken = 0;
            for (Future<MvcResult> result : results) {
                var response = result.get(30, TimeUnit.SECONDS).getResponse();
                if (response.getStatus() == 200) {
                    ok++;
                } else {
                    assertThat(response.getStatus()).isEqualTo(409);
                    assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                            .isEqualTo("HANDLE_TAKEN");
                    taken++;
                }
            }
            pool.shutdownNow();

            assertThat(ok).as("round %d winners", round).isEqualTo(1);
            assertThat(taken).isEqualTo(variants.size() - 1);
            assertThat(jdbc.queryForObject("select count(*) from profiles where lower(handle::text) = ?",
                    Long.class, base)).isEqualTo(1);
        }
    }

    @Test
    void theSameUserClaimingTheSameHandleTwiceAtOnceIsHarmless() throws Exception {
        TestUser user = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return claim(user, "double_tap").andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();
        assertThat(handleOf(user)).isEqualTo("double_tap");
        assertThat(Map.of("rows", jdbc.queryForObject("select count(*) from profiles", Long.class)))
                .containsEntry("rows", 1L);
    }
}
