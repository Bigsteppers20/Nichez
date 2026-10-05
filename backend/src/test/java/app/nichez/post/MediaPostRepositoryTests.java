package app.nichez.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nichez.TestPostgres;
import app.nichez.common.DbEnumConverter;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaKind;
import app.nichez.media.entity.MediaKindConverter;
import app.nichez.media.entity.MediaStatus;
import app.nichez.media.repository.MediaRepository;
import app.nichez.post.entity.Audience;
import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostStatus;
import app.nichez.post.entity.PostTag;
import app.nichez.post.entity.PostTagId;
import app.nichez.post.repository.PostRepository;
import app.nichez.post.repository.PostTagRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * The media and post entities against the real schema. Loading the context already proves
 * Hibernate's schema validation accepts every mapped column. Each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaPostRepositoryTests {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Autowired
    MediaRepository mediaRepository;
    @Autowired
    PostRepository postRepository;
    @Autowired
    PostTagRepository tagRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;

    private UUID author;
    private UUID niche;

    @BeforeEach
    void fixtures() {
        author = profile();
        niche = jdbc.queryForObject("select id from niches where slug = 'football'", UUID.class);
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id) values (?)", id);
        return id;
    }

    private Media newMedia() {
        return mediaRepository.saveAndFlush(
                new Media(UUID.randomUUID(), author, MediaKind.VIDEO, "video/mp4", "u/" + UUID.randomUUID(), NOW));
    }

    private Post newPost(Media media, String caption) {
        return postRepository.saveAndFlush(new Post(UUID.randomUUID(), author, niche, media.getId(), caption,
                Audience.EVERYONE, true, NOW));
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    // --- media -------------------------------------------------------------------------

    @Test
    void mediaRoundTripsEveryColumn() {
        Media media = newMedia();
        media.setStatus(MediaStatus.READY);
        media.setFinalKey("final/key.mp4");
        media.setPosterKey("final/poster.jpg");
        media.setBytes(12_345_678L);
        media.setWidth(1080);
        media.setHeight(1920);
        media.setDurationSeconds(42);
        mediaRepository.saveAndFlush(media);
        reload();

        Media loaded = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(loaded.getOwnerId()).isEqualTo(author);
        assertThat(loaded.getKind()).isEqualTo(MediaKind.VIDEO);
        assertThat(loaded.getDeclaredType()).isEqualTo("video/mp4");
        assertThat(loaded.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(loaded.getFinalKey()).isEqualTo("final/key.mp4");
        assertThat(loaded.getPosterKey()).isEqualTo("final/poster.jpg");
        assertThat(loaded.getBytes()).isEqualTo(12_345_678L);
        assertThat(loaded.getWidth()).isEqualTo(1080);
        assertThat(loaded.getHeight()).isEqualTo(1920);
        assertThat(loaded.getDurationSeconds()).isEqualTo(42);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select status from media where id = ?", String.class, media.getId()))
                .isEqualTo("ready");   // stored as lower-case text, not the enum name
    }

    @Test
    void aNewMediaStartsPendingAndInsertsWithoutAnExtraSelect() {
        Media media = newMedia();
        assertThat(media.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(media.isNew()).isFalse();   // once persisted it is an ordinary entity
        assertThat(new Media(UUID.randomUUID(), author, MediaKind.IMAGE, null, null, NOW).isNew()).isTrue();
    }

    @Test
    void updatedAtComesFromTheDatabaseTriggerAndIsReadBack() {
        Media media = newMedia();
        Instant first = media.getUpdatedAt();
        assertThat(first).isNotNull();

        media.setStatus(MediaStatus.UPLOADED);
        mediaRepository.saveAndFlush(media);

        assertThat(media.getUpdatedAt()).isAfterOrEqualTo(first);
        reload();
        assertThat(mediaRepository.findById(media.getId()).orElseThrow().getUpdatedAt()).isAfter(first);
    }

    @Test
    void mediaIsFoundByOwnerAndByStoredObject() {
        Media media = newMedia();
        assertThat(mediaRepository.findByIdAndOwnerId(media.getId(), author)).isPresent();
        assertThat(mediaRepository.findByIdAndOwnerId(media.getId(), UUID.randomUUID())).isEmpty();
        assertThat(mediaRepository.findByOriginalKey(media.getOriginalKey())).isPresent();
        assertThat(mediaRepository.findByOriginalKey("nope")).isEmpty();
    }

    @Test
    void theDatabaseStillRefusesWhatTheEntityAllows() {
        Media media = newMedia();
        media.setWidth(0);
        assertThatThrownBy(() -> mediaRepository.saveAndFlush(media)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- posts -------------------------------------------------------------------------

    @Test
    void postRoundTripsAndStartsProcessing() {
        Post post = newPost(newMedia(), "spent 3 hours on a pivot table");
        reload();

        Post loaded = postRepository.findById(post.getId()).orElseThrow();
        assertThat(loaded.getAuthorId()).isEqualTo(author);
        assertThat(loaded.getNicheId()).isEqualTo(niche);
        assertThat(loaded.getCaption()).isEqualTo("spent 3 hours on a pivot table");
        assertThat(loaded.getAudience()).isEqualTo(Audience.EVERYONE);
        assertThat(loaded.isAllowComments()).isTrue();
        assertThat(loaded.isSensitive()).isFalse();
        assertThat(loaded.getStatus()).isEqualTo(PostStatus.PROCESSING);
        assertThat(loaded.getReactionCount()).isZero();
        assertThat(loaded.getCommentCount()).isZero();
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getDeletedAt()).isNull();
        assertThat(loaded.getReshareOf()).isNull();
    }

    @Test
    void audienceIsStoredAsTheDocumentedWords() {
        Post post = postRepository.saveAndFlush(new Post(UUID.randomUUID(), author, niche, newMedia().getId(), null,
                Audience.NICHE_MEMBERS, false, NOW));
        assertThat(jdbc.queryForObject("select audience from posts where id = ?", String.class, post.getId()))
                .isEqualTo("niche_members");
        assertThat(jdbc.queryForObject("select allow_comments from posts where id = ?", Boolean.class, post.getId()))
                .isFalse();
    }

    @Test
    void publishingSetsTheStatusAndTimeTogether() {
        Post post = newPost(newMedia(), null);
        post.publish(NOW.plus(5, ChronoUnit.MINUTES));
        postRepository.saveAndFlush(post);
        reload();

        Post loaded = postRepository.findById(post.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(loaded.getPublishedAt()).isEqualTo(NOW.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void deletingIsASoftDeleteWithATimestamp() {
        Post post = newPost(newMedia(), null);
        post.markDeleted(NOW);
        postRepository.saveAndFlush(post);
        reload();
        Post loaded = postRepository.findById(post.getId()).orElseThrow();   // the row is still there
        assertThat(loaded.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(loaded.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void sensitiveCarriesItsReasonAndClearsIt() {
        Post post = newPost(newMedia(), null);
        post.setSensitive(true, "Graphic injury clip flagged by Matchday mods.");
        postRepository.saveAndFlush(post);
        reload();
        Post loaded = postRepository.findById(post.getId()).orElseThrow();
        assertThat(loaded.isSensitive()).isTrue();
        assertThat(loaded.getSensitiveReason()).startsWith("Graphic injury");

        loaded.setSensitive(false, "ignored");
        postRepository.saveAndFlush(loaded);
        reload();
        assertThat(postRepository.findById(post.getId()).orElseThrow().getSensitiveReason()).isNull();
    }

    @Test
    void anOverlongCaptionIsRejectedBeforeItReachesTheDatabase() {
        Media media = newMedia();
        assertThat(newPost(media, "x".repeat(Post.MAX_CAPTION_LENGTH))).isNotNull();
        Media other = newMedia();
        assertThatThrownBy(() -> newPost(other, "x".repeat(Post.MAX_CAPTION_LENGTH + 1)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void aMediaFileCanOnlyBackOnePost() {
        Media media = newMedia();
        newPost(media, "first");
        assertThatThrownBy(() -> newPost(media, "second")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPostIsFoundByStatusAndByItsMedia() {
        Media media = newMedia();
        Post post = newPost(media, null);
        assertThat(postRepository.findByIdAndStatus(post.getId(), PostStatus.PROCESSING)).isPresent();
        assertThat(postRepository.findByIdAndStatus(post.getId(), PostStatus.PUBLISHED)).isEmpty();
        assertThat(postRepository.findByMediaId(media.getId())).map(Post::getId).contains(post.getId());
    }

    @Test
    void feedQueriesReturnPublishedPostsNewestFirst() {
        Post oldest = published("oldest", NOW);
        Post newest = published("newest", NOW.plus(2, ChronoUnit.HOURS));
        Post middle = published("middle", NOW.plus(1, ChronoUnit.HOURS));
        newPost(newMedia(), "still processing");

        assertThat(postRepository.findByNicheIdAndStatusOrderByPublishedAtDesc(niche, PostStatus.PUBLISHED,
                PageRequest.of(0, 10))).extracting(Post::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(postRepository.findByAuthorIdAndStatusOrderByPublishedAtDesc(author, PostStatus.PUBLISHED,
                PageRequest.of(0, 2))).extracting(Post::getId).containsExactly(newest.getId(), middle.getId());
    }

    private Post published(String caption, Instant at) {
        Post post = newPost(newMedia(), caption);
        post.publish(at);
        return postRepository.saveAndFlush(post);
    }

    // --- tags --------------------------------------------------------------------------

    @Test
    void tagsAreSavedAndListedPerPost() {
        Post post = newPost(newMedia(), null);
        UUID friend = profile();
        UUID other = profile();
        tagRepository.saveAndFlush(new PostTag(post.getId(), friend));
        tagRepository.saveAndFlush(new PostTag(post.getId(), other));
        reload();

        assertThat(tagRepository.findByIdPostId(post.getId())).extracting(PostTag::getUserId)
                .containsExactlyInAnyOrder(friend, other);
        assertThat(tagRepository.existsById(new PostTagId(post.getId(), friend))).isTrue();
        assertThat(tagRepository.existsById(new PostTagId(post.getId(), UUID.randomUUID()))).isFalse();
    }

    @Test
    void theSamePersonCannotBeTaggedTwice() {
        Post post = newPost(newMedia(), null);
        UUID friend = profile();
        tagRepository.saveAndFlush(new PostTag(post.getId(), friend));
        assertThatThrownBy(() -> tagRepository.saveAndFlush(new PostTag(post.getId(), friend)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- the shared converter ----------------------------------------------------------

    @Test
    void theEnumConverterMapsBothWaysAndRefusesUnknownValues() {
        DbEnumConverter<MediaKind> converter = new MediaKindConverter();
        assertThat(converter.convertToDatabaseColumn(MediaKind.IMAGE)).isEqualTo("image");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("video")).isEqualTo(MediaKind.VIDEO);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThatThrownBy(() -> converter.convertToEntityAttribute("gif"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gif");
    }

    @Test
    void everyEnumValueMatchesAWordTheDatabaseAccepts() {
        List<String> media = List.of("pending", "uploaded", "processing", "ready", "failed");
        assertThat(java.util.Arrays.stream(MediaStatus.values()).map(MediaStatus::dbValue)).containsExactlyElementsOf(media);
        assertThat(java.util.Arrays.stream(PostStatus.values()).map(PostStatus::dbValue))
                .containsExactly("processing", "published", "failed", "removed", "deleted");
        assertThat(java.util.Arrays.stream(Audience.values()).map(Audience::dbValue))
                .containsExactly("everyone", "niche_members");
    }
}
