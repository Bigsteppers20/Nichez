package app.nichez.niche;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.niche.NicheStatsApiIntegrationTests.TestClockConfig;
import app.nichez.niche.service.NicheStatsService;
import app.nichez.security.TestTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
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

/** GET /niches/:id/stats against real posts, with real JWT verification and a movable clock. */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class NicheStatsApiIntegrationTests {

    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock nicheStatsTestClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NicheStatsService stats;

    private UUID football;
    private UUID other;
    private UUID author;
    private String token;

    @BeforeEach
    void clean() {
        clock.set(START);
        jdbc.execute("truncate table media_processing_jobs, post_tags, posts, media, profiles, user_niches, follows, niche_moderators cascade");
        football = jdbc.queryForObject("select id from niches where slug = 'football'", UUID.class);
        other = jdbc.queryForObject("select id from niches where id <> ? order by slug limit 1", UUID.class, football);
        stats.evict(football);
        stats.evict(other);
        author = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", author);
        jdbc.update("insert into profiles (id) values (?)", author);
        token = TestTokens.valid().subject(author.toString()).build();
    }

    private void post(UUID nicheId, String status, Duration publishedAgo) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("insert into media (id, owner_id, kind, status, created_at) values (?, ?, 'image', 'ready', ?)",
                mediaId, author, Timestamp.from(START));
        Timestamp publishedAt = publishedAgo == null ? null : Timestamp.from(START.minus(publishedAgo));
        Timestamp deletedAt = status.equals("deleted") || status.equals("removed") ? Timestamp.from(START) : null;
        jdbc.update("""
                insert into posts (id, author_id, niche_id, media_id, status, created_at, published_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), author, nicheId, mediaId, status, Timestamp.from(START.minus(Duration.ofDays(2))),
                publishedAt, deletedAt);
    }

    private void published(UUID nicheId, Duration ago) {
        post(nicheId, "published", ago);
    }

    private ResultActions stats(UUID nicheId) throws Exception {
        return mvc.perform(get("/api/v1/niches/{id}/stats", nicheId).header("Authorization", "Bearer " + token));
    }

    @Test
    void requiresASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/niches/{id}/stats", football)).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownNicheIs404() throws Exception {
        stats(UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NICHE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/niches/not-a-uuid/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void anEmptyNicheHasNoPostsAndADayAtTheTop() throws Exception {
        stats(football).andExpect(status().isOk())
                .andExpect(jsonPath("$.postsToday").value(0))
                .andExpect(jsonPath("$.freshWindow").value("the next day"));
    }

    @Test
    void countsOnlyPublishedPostsInThisNicheFromTheLast24Hours() throws Exception {
        published(football, Duration.ofMinutes(1));
        published(football, Duration.ofHours(24));              // exactly 24 h ago: still in
        published(football, Duration.ofHours(24).plusSeconds(1)); // just outside
        post(football, "processing", null);
        post(football, "failed", null);
        post(football, "deleted", Duration.ofHours(1));
        post(football, "removed", Duration.ofHours(1));
        published(other, Duration.ofHours(1));

        stats(football).andExpect(status().isOk())
                .andExpect(jsonPath("$.postsToday").value(2))
                .andExpect(jsonPath("$.freshWindow").value("the next 12 hours"));
    }

    @Test
    void aBusyNicheHasAShortWindow() throws Exception {
        for (int i = 0; i < 96; i++) {
            published(football, Duration.ofMinutes(15L * i));
        }
        stats(football).andExpect(jsonPath("$.postsToday").value(96))
                .andExpect(jsonPath("$.freshWindow").value("the next 15 minutes"));
    }

    @Test
    void resultsAreCachedForSixtySeconds() throws Exception {
        published(football, Duration.ofHours(1));
        stats(football).andExpect(jsonPath("$.postsToday").value(1))
                .andExpect(header().string("Cache-Control", Matchers.allOf(
                        Matchers.containsString("max-age=60"), Matchers.containsString("private"))));

        published(football, Duration.ofMinutes(1));
        clock.advance(Duration.ofSeconds(59));
        stats(football).andExpect(jsonPath("$.postsToday").value(1));

        clock.advance(Duration.ofSeconds(1));
        stats(football).andExpect(jsonPath("$.postsToday").value(2));
    }

    @Test
    void theCacheIsPerNiche() throws Exception {
        published(football, Duration.ofHours(1));
        stats(football).andExpect(jsonPath("$.postsToday").value(1));
        stats(other).andExpect(jsonPath("$.postsToday").value(0));
    }
}
