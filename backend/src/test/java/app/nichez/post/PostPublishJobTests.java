package app.nichez.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.post.PostPublishJobTests.TestClockConfig;
import app.nichez.post.repository.PostPublishRepository;
import app.nichez.post.service.PostPublishJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The publisher against the real schema. The scheduled run is pushed an hour out so the tests
 * drive {@code run()} by hand; a batch size of 2 makes the larger tests page.
 */
@SpringBootTest(properties = {
        "posts.publish-enabled=true",
        "posts.publish-interval=1h",
        "posts.publish-batch-size=2"
})
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PostPublishJobTests {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock publishTestClock() {
            return new MutableClock(NOW);
        }
    }

    /** A spy, so one test can make a single publish fail while everything else runs for real. */
    @MockitoSpyBean
    PostPublishRepository repository;
    @Autowired
    PostPublishJob job;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    private UUID author;
    private UUID niche;

    /** A queued post and its media. */
    private record Queued(UUID postId, UUID mediaId) {
    }

    @BeforeEach
    void clean() {
        clock.set(NOW);
        reset(repository);
        jdbc.execute("truncate table media_processing_jobs, post_tags, posts, media, profiles, user_niches, follows, niche_moderators cascade");
        author = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", author);
        jdbc.update("insert into profiles (id) values (?)", author);
        niche = jdbc.queryForObject("select id from niches where slug = 'football'", UUID.class);
    }

    /** A post waiting on its media, as {@code POST /posts} leaves it, with its job due at {@code dueAt}. */
    private Queued queued(String postStatus, Instant dueAt) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media (id, owner_id, kind, original_key, status, created_at)
                values (?, ?, 'image', ?, 'processing', ?)""",
                mediaId, author, "uploads/" + mediaId, Timestamp.from(NOW.minusSeconds(60)));
        UUID postId = UUID.randomUUID();
        Timestamp deletedAt = postStatus.equals("deleted") ? Timestamp.from(NOW.minusSeconds(30)) : null;
        jdbc.update("""
                insert into posts (id, author_id, niche_id, media_id, status, created_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?)""",
                postId, author, niche, mediaId, postStatus, Timestamp.from(NOW.minusSeconds(60)), deletedAt);
        jdbc.update("""
                insert into media_processing_jobs (media_id, post_id, status, available_at)
                values (?, ?, 'queued', ?)""", mediaId, postId, Timestamp.from(dueAt));
        return new Queued(postId, mediaId);
    }

    private Queued queued() {
        return queued("processing", NOW);
    }

    private String postStatus(Queued q) {
        return jdbc.queryForObject("select status from posts where id = ?", String.class, q.postId());
    }

    private String mediaStatus(Queued q) {
        return jdbc.queryForObject("select status from media where id = ?", String.class, q.mediaId());
    }

    private String jobStatus(Queued q) {
        return jdbc.queryForObject("select status from media_processing_jobs where media_id = ?", String.class,
                q.mediaId());
    }

    private int publishedAudits(Queued q) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where event_type = 'POST_PUBLISHED' and details->>'postId' = ?",
                Integer.class, q.postId().toString());
    }

    @Test
    void publishesAQueuedPostAndItsMedia() {
        Queued q = queued();

        assertThat(job.run()).isEqualTo(1);

        assertThat(postStatus(q)).isEqualTo("published");
        assertThat(jdbc.queryForObject("select published_at from posts where id = ?", Timestamp.class, q.postId())
                .toInstant()).isEqualTo(NOW);
        assertThat(mediaStatus(q)).isEqualTo("ready");
        assertThat(jobStatus(q)).isEqualTo("done");
        assertThat(jdbc.queryForObject("select attempts from media_processing_jobs where media_id = ?", Integer.class,
                q.mediaId())).isEqualTo(1);
    }

    @Test
    void theAuthorsPostCountFollowsByTrigger() {
        queued();
        queued();

        job.run();

        assertThat(jdbc.queryForObject("select post_count from profiles where id = ?", Integer.class, author))
                .isEqualTo(2);
    }

    @Test
    void recordsThePublishInTheAuditTrail() {
        Queued q = queued();

        job.run();

        assertThat(publishedAudits(q)).isEqualTo(1);
    }

    @Test
    void runningAgainChangesNothing() {
        Queued q = queued();
        job.run();
        clock.advance(Duration.ofMinutes(5));

        assertThat(job.run()).isZero();

        assertThat(jdbc.queryForObject("select published_at from posts where id = ?", Timestamp.class, q.postId())
                .toInstant()).isEqualTo(NOW);
        assertThat(publishedAudits(q)).isEqualTo(1);
    }

    @Test
    void leavesAJobAloneUntilItIsDue() {
        Queued q = queued("processing", NOW.plusSeconds(60));

        assertThat(job.run()).isZero();
        assertThat(postStatus(q)).isEqualTo("processing");
        assertThat(jobStatus(q)).isEqualTo("queued");

        clock.advance(Duration.ofMinutes(2));

        assertThat(job.run()).isEqualTo(1);
        assertThat(postStatus(q)).isEqualTo("published");
    }

    @Test
    void doesNotBringBackAPostDeletedWhileItWaited() {
        Queued q = queued("deleted", NOW);

        assertThat(job.run()).isZero();

        assertThat(postStatus(q)).isEqualTo("deleted");
        assertThat(mediaStatus(q)).isEqualTo("processing");
        assertThat(jobStatus(q)).isEqualTo("done");
        assertThat(publishedAudits(q)).isZero();
        assertThat(jdbc.queryForObject("select post_count from profiles where id = ?", Integer.class, author))
                .isZero();
    }

    @Test
    void pagesThroughMoreJobsThanOneBatch() {
        for (int i = 0; i < 5; i++) {
            queued();
        }

        assertThat(job.run()).isEqualTo(5);

        assertThat(jdbc.queryForObject("select count(*) from posts where status = 'published'", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void aFailedPublishLeavesThePostProcessingAndRetriesAMinuteLater() {
        Queued q = queued();
        doThrow(new IllegalStateException("boom")).when(repository).publish(eq(q.postId()), any());

        assertThat(job.run()).isZero();

        // The whole job rolled back: nothing half-done, and it is queued again, due in a minute.
        assertThat(postStatus(q)).isEqualTo("processing");
        assertThat(mediaStatus(q)).isEqualTo("processing");
        assertThat(jobStatus(q)).isEqualTo("queued");
        assertThat(jdbc.queryForObject("select available_at from media_processing_jobs where media_id = ?",
                Timestamp.class, q.mediaId()).toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        // Not due yet, so another pass does nothing; once it is due and the fault is gone, it publishes.
        reset(repository);
        assertThat(job.run()).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(job.run()).isEqualTo(1);
        assertThat(postStatus(q)).isEqualTo("published");
    }

    @Test
    void oneFailingJobDoesNotHoldUpTheOthersInItsBatch() {
        Queued bad = queued();
        Queued good = queued("processing", NOW.plusSeconds(1));
        doThrow(new IllegalStateException("boom")).when(repository).publish(eq(bad.postId()), any());

        clock.advance(Duration.ofSeconds(2));

        assertThat(job.run()).isEqualTo(1);

        assertThat(postStatus(good)).isEqualTo("published");
        assertThat(postStatus(bad)).isEqualTo("processing");
        assertThat(jobStatus(bad)).isEqualTo("queued");
    }
}
