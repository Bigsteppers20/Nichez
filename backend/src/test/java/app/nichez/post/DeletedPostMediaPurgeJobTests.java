package app.nichez.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.post.DeletedPostMediaPurgeJobTests.TestClockConfig;
import app.nichez.post.service.DeletedPostMediaPurgeJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The daily purge against the real schema, with object storage mocked. A batch size of 2 makes
 * every test with more than two candidates page.
 */
@SpringBootTest(properties = "posts.purge-batch-size=2")
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class DeletedPostMediaPurgeJobTests {

    private static final Instant NOW = Instant.parse("2026-11-10T03:30:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock purgeTestClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoBean
    S3Client s3;
    @Autowired
    DeletedPostMediaPurgeJob job;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    private UUID author;
    private UUID niche;

    @BeforeEach
    void clean() {
        clock.set(NOW);
        reset(s3);
        jdbc.execute("truncate table media_processing_jobs, post_tags, posts, media, profiles, user_niches, follows, niche_moderators cascade");
        author = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", author);
        jdbc.update("insert into profiles (id) values (?)", author);
        niche = jdbc.queryForObject("select id from niches where slug = 'football'", UUID.class);
    }

    /** A post with the given status, taken down {@code ago} before now. Returns the media id. */
    private UUID post(String status, Duration ago) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media (id, owner_id, kind, original_key, final_key, poster_key, status, created_at)
                values (?, ?, 'video', ?, ?, ?, 'ready', ?)""",
                mediaId, author, "uploads/" + mediaId, "media/" + mediaId, "posters/" + mediaId,
                Timestamp.from(NOW.minus(Duration.ofDays(60))));
        Timestamp deletedAt = ago == null ? null : Timestamp.from(NOW.minus(ago));
        Timestamp publishedAt = Timestamp.from(NOW.minus(Duration.ofDays(59)));
        jdbc.update("""
                insert into posts (id, author_id, niche_id, media_id, status, created_at, published_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), author, niche, mediaId, status, publishedAt, publishedAt, deletedAt);
        return mediaId;
    }

    private boolean purged(UUID mediaId) {
        return jdbc.queryForObject("select purged_at is not null from media where id = ?", Boolean.class, mediaId);
    }

    private List<String> deletedKeys() {
        ArgumentCaptor<DeleteObjectRequest> requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, atLeastOnce()).deleteObject(requests.capture());
        requests.getAllValues().forEach(r -> assertThat(r.bucket()).isEqualTo("media"));
        return requests.getAllValues().stream().map(DeleteObjectRequest::key).toList();
    }

    private long auditCount(UUID mediaId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where event_type = 'MEDIA_PURGED' and details->>'mediaId' = ?",
                Long.class, mediaId.toString());
    }

    @Test
    void deletesEveryObjectOfPostsTakenDownMoreThan30DaysAgo() {
        UUID deleted = post("deleted", Duration.ofDays(31));
        UUID removed = post("removed", Duration.ofDays(30).plusSeconds(1));

        assertThat(job.run()).isEqualTo(2);

        assertThat(deletedKeys()).containsExactlyInAnyOrder(
                "uploads/" + deleted, "media/" + deleted, "posters/" + deleted,
                "uploads/" + removed, "media/" + removed, "posters/" + removed);
        assertThat(purged(deleted)).isTrue();
        assertThat(purged(removed)).isTrue();
        assertThat(auditCount(deleted)).isEqualTo(1);
        assertThat(auditCount(removed)).isEqualTo(1);
    }

    @Test
    void leavesRecentTakedownsAndLivePostsAlone() {
        UUID recent = post("deleted", Duration.ofDays(29));
        UUID exactlyThirty = post("removed", Duration.ofDays(30));
        UUID live = post("published", null);

        assertThat(job.run()).isZero();

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(purged(recent)).isFalse();
        assertThat(purged(exactlyThirty)).isFalse();
        assertThat(purged(live)).isFalse();
    }

    @Test
    void aPurgedMediaIsNotTouchedAgain() {
        UUID media = post("deleted", Duration.ofDays(40));
        assertThat(job.run()).isEqualTo(1);
        clearInvocations(s3);

        assertThat(job.run()).isZero();

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(auditCount(media)).isEqualTo(1);
    }

    @Test
    void pagesThroughMoreCandidatesThanOneBatch() {
        List<UUID> media = List.of(
                post("deleted", Duration.ofDays(31)), post("deleted", Duration.ofDays(32)),
                post("removed", Duration.ofDays(33)), post("deleted", Duration.ofDays(34)),
                post("removed", Duration.ofDays(35)));

        assertThat(job.run()).isEqualTo(5);

        media.forEach(id -> assertThat(purged(id)).isTrue());
        assertThat(deletedKeys()).hasSize(15);
    }

    @Test
    void aStorageFailureSkipsThatMediaAndTheNextRunRetriesIt() {
        List<UUID> before = List.of(post("deleted", Duration.ofDays(35)), post("deleted", Duration.ofDays(34)));
        UUID failing = post("deleted", Duration.ofDays(33));
        List<UUID> after = List.of(post("deleted", Duration.ofDays(32)), post("deleted", Duration.ofDays(31)));
        when(s3.deleteObject(argThat((DeleteObjectRequest r) -> r != null && r.key().equals("media/" + failing))))
                .thenThrow(S3Exception.builder().message("boom").statusCode(500).build());

        assertThat(job.run()).isEqualTo(4);

        before.forEach(id -> assertThat(purged(id)).isTrue());
        after.forEach(id -> assertThat(purged(id)).isTrue());
        assertThat(purged(failing)).isFalse();
        assertThat(auditCount(failing)).isZero();

        reset(s3);
        assertThat(job.run()).isEqualTo(1);
        assertThat(purged(failing)).isTrue();
        assertThat(deletedKeys()).containsExactlyInAnyOrder(
                "uploads/" + failing, "media/" + failing, "posters/" + failing);
    }

    @Test
    void retentionIsMeasuredFromTheTakedownNotTheUpload() {
        UUID media = post("deleted", Duration.ofDays(10));

        clock.advance(Duration.ofDays(19));
        assertThat(job.run()).isZero();

        clock.advance(Duration.ofDays(2));
        assertThat(job.run()).isEqualTo(1);
        assertThat(purged(media)).isTrue();
    }
}
