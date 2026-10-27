package app.nichez.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.niche.service.NicheStatsService;
import app.nichez.post.PostDeleteApiIntegrationTests.TestClockConfig;
import app.nichez.security.TestTokens;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * DELETE /posts/:id for every kind of caller: the author, a moderator of the post's niche, a
 * moderator of another niche, an admin (app_metadata), a fake admin (user_metadata) and a stranger.
 * Real database, real JWT verification, movable clock.
 */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PostDeleteApiIntegrationTests {

    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock postDeleteTestClock() {
            return new MutableClock(START);
        }
    }

    private record TestUser(UUID id, String token) {
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NicheStatsService nicheStats;

    private UUID football;
    private UUID cooking;

    @BeforeEach
    void clean() {
        clock.set(START);
        jdbc.execute("truncate table media_processing_jobs, post_tags, posts, media, profiles, user_niches, follows, niche_moderators cascade");
        football = nicheId("football");
        nicheStats.evict(football);
        cooking = jdbc.queryForObject("select id from niches where id <> ? order by slug limit 1", UUID.class,
                football);
    }

    // --- helpers ---------------------------------------------------------------------

    private UUID nicheId(String slug) {
        return jdbc.queryForObject("select id from niches where slug = ?", UUID.class, slug);
    }

    private TestUser newUser() {
        return newUser(Map.of("provider", "phone"), Map.of());
    }

    private TestUser newUser(Map<String, Object> appMetadata, Map<String, Object> userMetadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id) values (?)", id);
        String token = TestTokens.valid().subject(id.toString())
                .appMetadata(appMetadata).userMetadata(userMetadata).build();
        return new TestUser(id, token);
    }

    private TestUser newAdmin() {
        return newUser(Map.of("provider", "phone", "role", "admin"), Map.of());
    }

    private void makeModerator(TestUser user, UUID nicheId) {
        jdbc.update("insert into niche_moderators (niche_id, user_id) values (?, ?)", nicheId, user.id());
    }

    private UUID post(TestUser author, UUID nicheId, String status) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media (id, owner_id, kind, original_key, final_key, status, created_at)
                values (?, ?, 'image', ?, ?, 'ready', ?)""",
                mediaId, author.id(), "uploads/" + mediaId, "media/" + mediaId, Timestamp.from(START));
        UUID postId = UUID.randomUUID();
        Timestamp publishedAt = status.equals("published") ? Timestamp.from(START.minus(Duration.ofHours(1))) : null;
        Timestamp deletedAt = status.equals("deleted") ? Timestamp.from(START) : null;
        jdbc.update("""
                insert into posts (id, author_id, niche_id, media_id, status, created_at, published_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""",
                postId, author.id(), nicheId, mediaId, status, Timestamp.from(START.minus(Duration.ofHours(2))),
                publishedAt, deletedAt);
        return postId;
    }

    private UUID publishedPost(TestUser author, UUID nicheId) {
        return post(author, nicheId, "published");
    }

    private ResultActions deletePost(TestUser caller, UUID postId) throws Exception {
        return mvc.perform(delete("/api/v1/posts/{id}", postId).header("Authorization", "Bearer " + caller.token()));
    }

    private void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Post not found."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private String statusOf(UUID postId) {
        return jdbc.queryForObject("select status from posts where id = ?", String.class, postId);
    }

    private Instant deletedAtOf(UUID postId) {
        Timestamp at = jdbc.queryForObject("select deleted_at from posts where id = ?", Timestamp.class, postId);
        return at == null ? null : at.toInstant();
    }

    private int postCountOf(TestUser user) {
        return jdbc.queryForObject("select post_count from profiles where id = ?", Integer.class, user.id());
    }

    private List<Map<String, Object>> auditFor(UUID postId) {
        return jdbc.queryForList("""
                select event_type, details->>'actorId' as actor_id, details->>'actorRole' as actor_role,
                       details->>'authorId' as author_id, details->>'nicheId' as niche_id,
                       details->>'previousStatus' as previous_status
                from audit_events where details->>'postId' = ? order by id""", postId.toString());
    }

    // --- access ----------------------------------------------------------------------

    @Test
    void requiresASignedInUser() throws Exception {
        UUID postId = publishedPost(newUser(), football);
        mvc.perform(delete("/api/v1/posts/{id}", postId)).andExpect(status().isUnauthorized());
        assertThat(statusOf(postId)).isEqualTo("published");
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        mvc.perform(delete("/api/v1/posts/not-a-uuid").header("Authorization", "Bearer " + newUser().token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- author ----------------------------------------------------------------------

    @Test
    void theAuthorDeletesTheirPost() throws Exception {
        TestUser author = newUser();
        UUID postId = publishedPost(author, football);
        assertThat(postCountOf(author)).isEqualTo(1);

        deletePost(author, postId).andExpect(status().isNoContent()).andExpect(content().string(""));

        assertThat(statusOf(postId)).isEqualTo("deleted");
        assertThat(deletedAtOf(postId)).isEqualTo(START);
        assertThat(postCountOf(author)).isZero();
        assertThat(auditFor(postId)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "POST_DELETED")
                    .containsEntry("actor_role", "author")
                    .containsEntry("actor_id", author.id().toString())
                    .containsEntry("author_id", author.id().toString())
                    .containsEntry("niche_id", football.toString())
                    .containsEntry("previous_status", "published");
        });
    }

    @Test
    void theAuthorCanDeleteAPostStillProcessingWithoutTouchingTheCount() throws Exception {
        TestUser author = newUser();
        publishedPost(author, football);
        UUID processing = post(author, football, "processing");

        deletePost(author, processing).andExpect(status().isNoContent());

        assertThat(statusOf(processing)).isEqualTo("deleted");
        assertThat(postCountOf(author)).isEqualTo(1);
    }

    @Test
    void anAuthorWhoModeratesTheNicheStillDeletesRatherThanRemoves() throws Exception {
        TestUser author = newUser();
        makeModerator(author, football);
        UUID postId = publishedPost(author, football);

        deletePost(author, postId).andExpect(status().isNoContent());

        assertThat(statusOf(postId)).isEqualTo("deleted");
        assertThat(auditFor(postId)).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("actor_role", "author"));
    }

    @Test
    void aSecondDeleteIs404AndCountsOnlyOnce() throws Exception {
        TestUser author = newUser();
        publishedPost(author, football);
        UUID postId = publishedPost(author, football);

        deletePost(author, postId).andExpect(status().isNoContent());
        expectNotFound(deletePost(author, postId));

        assertThat(postCountOf(author)).isEqualTo(1);
        assertThat(auditFor(postId)).hasSize(1);
    }

    // --- moderator -------------------------------------------------------------------

    @Test
    void aModeratorOfTheNicheRemovesThePost() throws Exception {
        TestUser author = newUser();
        TestUser moderator = newUser();
        makeModerator(moderator, football);
        UUID postId = publishedPost(author, football);

        deletePost(moderator, postId).andExpect(status().isNoContent());

        assertThat(statusOf(postId)).isEqualTo("removed");
        assertThat(deletedAtOf(postId)).isEqualTo(START);
        assertThat(postCountOf(author)).isZero();
        assertThat(auditFor(postId)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "POST_REMOVED")
                    .containsEntry("actor_role", "moderator")
                    .containsEntry("actor_id", moderator.id().toString())
                    .containsEntry("author_id", author.id().toString());
        });
    }

    @Test
    void aModeratorOfAnotherNicheGets404() throws Exception {
        TestUser author = newUser();
        TestUser moderator = newUser();
        makeModerator(moderator, cooking);
        UUID postId = publishedPost(author, football);

        expectNotFound(deletePost(moderator, postId));

        assertThat(statusOf(postId)).isEqualTo("published");
        assertThat(postCountOf(author)).isEqualTo(1);
        assertThat(auditFor(postId)).isEmpty();
    }

    @Test
    void theAuthorCannotDeleteAPostModeratorsRemoved() throws Exception {
        TestUser author = newUser();
        TestUser moderator = newUser();
        makeModerator(moderator, football);
        UUID postId = publishedPost(author, football);
        deletePost(moderator, postId).andExpect(status().isNoContent());

        expectNotFound(deletePost(author, postId));

        assertThat(statusOf(postId)).isEqualTo("removed");
    }

    // --- admin -----------------------------------------------------------------------

    @Test
    void anAdminRemovesAnyPost() throws Exception {
        TestUser author = newUser();
        TestUser admin = newAdmin();
        UUID postId = publishedPost(author, football);

        deletePost(admin, postId).andExpect(status().isNoContent());

        assertThat(statusOf(postId)).isEqualTo("removed");
        assertThat(postCountOf(author)).isZero();
        assertThat(auditFor(postId)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "POST_REMOVED")
                    .containsEntry("actor_role", "admin")
                    .containsEntry("actor_id", admin.id().toString());
        });
    }

    @Test
    void anAdminRoleInUserMetadataGrantsNothing() throws Exception {
        TestUser author = newUser();
        TestUser fake = newUser(Map.of("provider", "phone"), Map.of("role", "admin"));
        UUID postId = publishedPost(author, football);

        expectNotFound(deletePost(fake, postId));

        assertThat(statusOf(postId)).isEqualTo("published");
    }

    // --- anyone else -----------------------------------------------------------------

    @Test
    void aStrangerGetsTheSame404AsForAPostThatDoesNotExist() throws Exception {
        TestUser stranger = newUser();
        UUID postId = publishedPost(newUser(), football);

        String forExisting = deletePost(stranger, postId).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String forMissing = deletePost(stranger, UUID.randomUUID()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Identical apart from the fields that echo the request (its path and id).
        String perRequest = "\"(requestId|instance)\":\"[^\"]+\"";
        assertThat(forExisting.replaceAll(perRequest, "")).isEqualTo(forMissing.replaceAll(perRequest, ""));
        expectNotFound(deletePost(stranger, postId));
        assertThat(statusOf(postId)).isEqualTo("published");
        assertThat(auditFor(postId)).isEmpty();
    }

    // --- side effects ----------------------------------------------------------------

    @Test
    void deletingDropsTheNichesCachedStatsAtOnce() throws Exception {
        TestUser author = newUser();
        UUID postId = publishedPost(author, football);
        mvc.perform(get("/api/v1/niches/{id}/stats", football).header("Authorization", "Bearer " + author.token()))
                .andExpect(jsonPath("$.postsToday").value(1));

        deletePost(author, postId).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/niches/{id}/stats", football).header("Authorization", "Bearer " + author.token()))
                .andExpect(jsonPath("$.postsToday").value(0));
    }

    @Test
    void whenTheAuthorAndAModeratorDeleteAtOnceExactlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestUser author = newUser();
            TestUser moderator = newUser();
            makeModerator(moderator, football);
            UUID postId = publishedPost(author, football);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (TestUser caller : List.of(author, moderator, author, moderator)) {
                results.add(pool.submit(() -> {
                    go.await();
                    return deletePost(caller, postId).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            pool.shutdownNow();

            assertThat(statuses).as("round %d", round).containsOnlyOnce(204).containsOnly(204, 404);
            assertThat(postCountOf(author)).isZero();
            assertThat(auditFor(postId)).hasSize(1);
        }
    }
}
