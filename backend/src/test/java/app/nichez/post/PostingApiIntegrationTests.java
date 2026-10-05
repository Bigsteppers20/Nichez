package app.nichez.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.post.PostingApiIntegrationTests.TestClockConfig;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Flow 4, posting: POST /uploads/sign, POST /posts and GET /posts/:id, with every refusal.
 * Real database and JWT verification; object storage is mocked (presigning is offline, so the
 * real presigner runs). Small rate limits make the 429s cheap to reach.
 */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER,
        "uploads.sign-per-hour=4",
        "posts.create-per-hour=3",
        "uploads.max-image-size=1MB",
        "uploads.max-video-size=5MB"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PostingApiIntegrationTests {

    private static final Instant START = Instant.parse("2026-10-05T09:00:00Z");

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1);
    private static final byte[] MP4 = bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock postingTestClock() {
            return new MutableClock(START);
        }
    }

    private record TestUser(UUID id, String token) {
    }

    @MockitoBean
    S3Client s3;
    @MockitoBean
    BlockPolicy blocks;
    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private UUID football;
    private UUID cooking;

    @BeforeEach
    void clean() {
        clock.set(START);
        reset(s3, blocks);
        jdbc.execute("truncate table media_processing_jobs, post_tags, posts, media, profiles, user_niches, "
                + "follows, niche_moderators");
        jdbc.execute("delete from audit_events where event_type = 'POST_CREATED'");
        football = jdbc.queryForObject("select id from niches where slug = 'football'", UUID.class);
        cooking = jdbc.queryForObject("select id from niches where id <> ? order by slug limit 1", UUID.class,
                football);
    }

    // --- helpers ---------------------------------------------------------------------

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private TestUser newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id, handle, name) values (?, ?, ?)", id,
                "u" + id.toString().substring(0, 8), "User " + id.toString().substring(0, 4));
        return new TestUser(id, TestTokens.valid().subject(id.toString()).build());
    }

    private TestUser member(UUID nicheId) {
        TestUser user = newUser();
        join(user, nicheId);
        return user;
    }

    private void join(TestUser user, UUID nicheId) {
        jdbc.update("insert into user_niches (user_id, niche_id) values (?, ?)", user.id(), nicheId);
    }

    /** Storage answers as if {@code content} (its first bytes) of {@code size} bytes is stored. */
    private void stored(byte[] content, long size) {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(size).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));
    }

    private ResultActions sign(TestUser user, String contentType, long size) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentType", contentType);
        body.put("bytes", size);
        return mvc.perform(post("/api/v1/uploads/sign")
                .header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private UUID signedMedia(TestUser user, String contentType) throws Exception {
        String body = sign(user, contentType, 1000).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("mediaId").asText());
    }

    private Map<String, Object> postBody(UUID mediaId, UUID nicheId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mediaId", mediaId);
        body.put("caption", "spent 3 hours on a pivot table");
        body.put("nicheId", nicheId);
        return body;
    }

    private ResultActions createPost(TestUser user, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/posts")
                .header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    /** A post created through the API, still processing. */
    private UUID createdPost(TestUser author, UUID nicheId, Map<String, Object> extra) throws Exception {
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1000);
        Map<String, Object> body = postBody(mediaId, nicheId);
        body.putAll(extra);
        String response = createPost(author, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private void setStatus(UUID postId, String status) {
        jdbc.update("""
                update posts set status = ?,
                    published_at = case when ? = 'published' then ? else published_at end,
                    deleted_at = case when ? in ('deleted', 'removed') then ? else deleted_at end
                where id = ?""",
                status, status, Timestamp.from(START), status, Timestamp.from(START), postId);
    }

    private ResultActions getPost(TestUser viewer, UUID postId) throws Exception {
        return mvc.perform(get("/api/v1/posts/{id}", postId).header("Authorization", "Bearer " + viewer.token()));
    }

    private void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String mediaStatus(UUID mediaId) {
        return jdbc.queryForObject("select status from media where id = ?", String.class, mediaId);
    }

    // --- POST /uploads/sign ---------------------------------------------------------

    @Test
    void signingCreatesPendingMediaAndAPresignedPut() throws Exception {
        TestUser user = newUser();

        String body = sign(user, "image/png", 1000).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json.readTree(body);
        UUID mediaId = UUID.fromString(response.get("mediaId").asText());
        String url = response.get("uploadUrl").asText();
        assertThat(url).startsWith("http://localhost:9000/media/uploads/" + user.id() + "/" + mediaId + "?")
                .contains("X-Amz-Signature=", "X-Amz-Expires=900");
        Map<String, Object> row = jdbc.queryForMap(
                "select owner_id, kind, declared_type, original_key, bytes, status from media where id = ?", mediaId);
        assertThat(row).containsEntry("owner_id", user.id())
                .containsEntry("kind", "image")
                .containsEntry("declared_type", "image/png")
                .containsEntry("original_key", "uploads/" + user.id() + "/" + mediaId)
                .containsEntry("bytes", 1000L)
                .containsEntry("status", "pending");
    }

    @Test
    void signingCreatesTheProfileRowOfAUserWhoHasNone() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        TestUser user = new TestUser(id, TestTokens.valid().subject(id.toString()).build());

        sign(user, "video/mp4", 1000).andExpect(status().isOk());

        assertThat(count("select count(*) from profiles where id = ?", id)).isEqualTo(1);
    }

    @Test
    void anUnsupportedTypeIs400() throws Exception {
        TestUser user = newUser();
        expectError(sign(user, "application/pdf", 1000), 400, "UNSUPPORTED_MEDIA_TYPE");
        expectError(sign(user, "image/svg+xml", 1000), 400, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(count("select count(*) from media where owner_id = ?", user.id())).isZero();
    }

    @Test
    void aFileOverItsKindsCapIs413() throws Exception {
        TestUser user = newUser();
        expectError(sign(user, "image/jpeg", 1024 * 1024 + 1), 413, "MEDIA_TOO_LARGE");
        expectError(sign(user, "video/mp4", 5L * 1024 * 1024 + 1), 413, "MEDIA_TOO_LARGE");
        // A size too big for an image is fine for a video.
        sign(user, "video/mp4", 1024 * 1024 + 1).andExpect(status().isOk());
        assertThat(count("select count(*) from media where owner_id = ?", user.id())).isEqualTo(1);
    }

    @Test
    void signingValidatesTheBody() throws Exception {
        TestUser user = newUser();
        expectError(sign(user, "image/png", 0), 400, "VALIDATION_FAILED");
        expectError(sign(user, "", 1000), 400, "VALIDATION_FAILED");
        expectError(mvc.perform(post("/api/v1/uploads/sign")
                .header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\"}")), 400, "VALIDATION_FAILED");
    }

    @Test
    void signingIsRateLimitedPerUser() throws Exception {
        TestUser user = newUser();
        for (int i = 0; i < 4; i++) {
            sign(user, "image/png", 1000).andExpect(status().isOk());
        }
        expectError(sign(user, "image/png", 1000), 429, "RATE_LIMITED");
        sign(user, "image/png", 1000).andExpect(header().exists("Retry-After"));
        sign(newUser(), "image/png", 1000).andExpect(status().isOk());
    }

    @Test
    void signingNeedsASignedInUser() throws Exception {
        mvc.perform(post("/api/v1/uploads/sign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"bytes\":1000}"))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /posts: success ---------------------------------------------------------

    @Test
    void creatingAPostQueuesItsMediaForProcessing() throws Exception {
        TestUser author = member(football);
        TestUser friend = newUser();
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 900);
        Map<String, Object> body = postBody(mediaId, football);
        body.put("audience", "niche_members");
        body.put("allowComments", false);
        body.put("taggedUserIds", List.of(friend.id(), friend.id()));

        String response = createPost(author, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/posts/")))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.author.id").value(author.id().toString()))
                .andExpect(jsonPath("$.author.handle").isNotEmpty())
                .andExpect(jsonPath("$.nicheId").value(football.toString()))
                .andExpect(jsonPath("$.niche").isNotEmpty())
                .andExpect(jsonPath("$.caption").value("spent 3 hours on a pivot table"))
                .andExpect(jsonPath("$.audience").value("niche_members"))
                .andExpect(jsonPath("$.allowComments").value(false))
                .andExpect(jsonPath("$.isVideo").value(false))
                .andExpect(jsonPath("$.mediaUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.posterUrl").doesNotExist())
                .andExpect(jsonPath("$.reactions").value(0))
                .andExpect(jsonPath("$.comments").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-05T09:00:00Z"))
                .andExpect(jsonPath("$.timeAgo").value("now"))
                .andReturn().getResponse().getContentAsString();

        UUID postId = UUID.fromString(json.readTree(response).get("id").asText());
        assertThat(jdbc.queryForMap("select author_id, media_id, status, audience, allow_comments from posts where id = ?",
                postId)).containsEntry("author_id", author.id()).containsEntry("media_id", mediaId)
                .containsEntry("status", "processing").containsEntry("audience", "niche_members")
                .containsEntry("allow_comments", false);
        assertThat(mediaStatus(mediaId)).isEqualTo("processing");
        assertThat(jdbc.queryForObject("select bytes from media where id = ?", Long.class, mediaId)).isEqualTo(900L);
        assertThat(jdbc.queryForList("select user_id from post_tags where post_id = ?", UUID.class, postId))
                .containsExactly(friend.id());
        assertThat(jdbc.queryForMap("select post_id, status from media_processing_jobs where media_id = ?", mediaId))
                .containsEntry("post_id", postId).containsEntry("status", "queued");
        assertThat(jdbc.queryForList("""
                select details->>'authorId' as author_id, details->>'mediaId' as media_id
                from audit_events where event_type = 'POST_CREATED' and details->>'postId' = ?""",
                postId.toString())).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("author_id", author.id().toString())
                .containsEntry("media_id", mediaId.toString()));
        // Processing posts are not counted until published.
        assertThat(count("select post_count from profiles where id = ?", author.id())).isZero();
    }

    @Test
    void audienceAndCommentsDefaultWhenLeftOut() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "video/mp4");
        stored(MP4, 4000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mediaId", mediaId);
        body.put("nicheId", football);

        createPost(author, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("everyone"))
                .andExpect(jsonPath("$.allowComments").value(true))
                .andExpect(jsonPath("$.isVideo").value(true))
                .andExpect(jsonPath("$.caption").doesNotExist());
    }

    // --- POST /posts: refusals --------------------------------------------------------

    @Test
    void someoneElsesMediaIsNotFound() throws Exception {
        TestUser owner = newUser();
        TestUser thief = member(football);
        UUID mediaId = signedMedia(owner, "image/png");
        stored(PNG, 1000);

        expectError(createPost(thief, postBody(mediaId, football)), 404, "MEDIA_NOT_FOUND");
        expectError(createPost(thief, postBody(UUID.randomUUID(), football)), 404, "MEDIA_NOT_FOUND");
        assertThat(mediaStatus(mediaId)).isEqualTo("pending");
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void mediaCanBackOnlyOnePost() throws Exception {
        TestUser author = member(football);
        join(author, cooking);
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1000);
        createPost(author, postBody(mediaId, football)).andExpect(status().isCreated());

        expectError(createPost(author, postBody(mediaId, cooking)), 409, "MEDIA_ALREADY_USED");

        assertThat(count("select count(*) from posts where media_id = ?", mediaId)).isEqualTo(1);
        assertThat(count("select count(*) from media_processing_jobs where media_id = ?", mediaId)).isEqualTo(1);
    }

    @Test
    void postingToANicheYouHaveNotJoinedIs403() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1000);

        expectError(createPost(author, postBody(mediaId, cooking)), 403, "NOT_A_NICHE_MEMBER");

        assertThat(count("select count(*) from posts")).isZero();
        assertThat(mediaStatus(mediaId)).isEqualTo("pending");
    }

    @Test
    void anUnknownNicheIs404() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");

        expectError(createPost(author, postBody(mediaId, UUID.randomUUID())), 404, "NICHE_NOT_FOUND");
    }

    @Test
    void taggingSomeoneWhoDoesNotExistIs400() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1000);
        Map<String, Object> body = postBody(mediaId, football);
        body.put("taggedUserIds", List.of(newUser().id(), UUID.randomUUID()));

        expectError(createPost(author, body), 400, "TAGGED_USER_NOT_FOUND");
        assertThat(count("select count(*) from posts")).isZero();
    }

    @Test
    void taggingSomeoneWhoBlockedTheAuthorLooksTheSameAsAnUnknownUser() throws Exception {
        TestUser author = member(football);
        TestUser blocker = newUser();
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1000);
        when(blocks.blockedEitherWay(eq(author.id()), anyCollection())).thenReturn(Set.of(blocker.id()));
        Map<String, Object> body = postBody(mediaId, football);
        body.put("taggedUserIds", List.of(blocker.id()));

        expectError(createPost(author, body), 400, "TAGGED_USER_NOT_FOUND")
                .andExpect(jsonPath("$.detail").value("One or more tagged people could not be found."));
        assertThat(count("select count(*) from posts")).isZero();
    }

    @Test
    void moreThanTenTagsIs400() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        List<UUID> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(UUID.randomUUID());
        }
        Map<String, Object> body = postBody(mediaId, football);
        body.put("taggedUserIds", eleven);

        expectError(createPost(author, body), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("taggedUserIds"));
    }

    @Test
    void aPngDeclaredAsMp4IsRefused() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "video/mp4");
        stored(PNG, 1000);

        expectError(createPost(author, postBody(mediaId, football)), 400, "MEDIA_TYPE_MISMATCH");

        assertThat(count("select count(*) from posts")).isZero();
        assertThat(count("select count(*) from media_processing_jobs")).isZero();
        assertThat(mediaStatus(mediaId)).isEqualTo("pending");
    }

    @Test
    void aJpegDeclaredAsPngAndUnrecognisableBytesAreRefused() throws Exception {
        TestUser author = member(football);
        UUID jpegAsPng = signedMedia(author, "image/png");
        stored(JPEG, 1000);
        expectError(createPost(author, postBody(jpegAsPng, football)), 400, "MEDIA_TYPE_MISMATCH");

        stored("<html><body>hi</body></html>".getBytes(), 1000);
        expectError(createPost(author, postBody(jpegAsPng, football)), 400, "MEDIA_TYPE_MISMATCH");
    }

    @Test
    void aStoredFileOverTheCapIs413() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        stored(PNG, 1024 * 1024 + 1);

        expectError(createPost(author, postBody(mediaId, football)), 413, "MEDIA_TOO_LARGE");
        assertThat(count("select count(*) from posts")).isZero();
    }

    @Test
    void postingBeforeTheUploadFinishedIs409AndCanBeRetried() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        expectError(createPost(author, postBody(mediaId, football)), 409, "MEDIA_NOT_UPLOADED");

        stored(PNG, 1000);
        createPost(author, postBody(mediaId, football)).andExpect(status().isCreated());
    }

    @Test
    void storageBeingDownIs502() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("bucket media key x").build());

        expectError(createPost(author, postBody(mediaId, football)), 502, "STORAGE_UNAVAILABLE")
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("uploads/"))));
        assertThat(mediaStatus(mediaId)).isEqualTo("pending");
    }

    @Test
    void creatingValidatesTheBody() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");

        Map<String, Object> badAudience = postBody(mediaId, football);
        badAudience.put("audience", "friends");
        expectError(createPost(author, badAudience), 400, "VALIDATION_FAILED");

        Map<String, Object> longCaption = postBody(mediaId, football);
        longCaption.put("caption", "x".repeat(2201));
        expectError(createPost(author, longCaption), 400, "VALIDATION_FAILED");

        Map<String, Object> noMedia = postBody(mediaId, football);
        noMedia.remove("mediaId");
        expectError(createPost(author, noMedia), 400, "VALIDATION_FAILED");
    }

    @Test
    void creatingIsRateLimitedPerUser() throws Exception {
        TestUser author = member(football);
        UUID mediaId = signedMedia(author, "image/png");
        for (int i = 0; i < 3; i++) {
            createPost(author, postBody(UUID.randomUUID(), football)).andExpect(status().isNotFound());
        }
        expectError(createPost(author, postBody(mediaId, football)), 429, "RATE_LIMITED");
    }

    @Test
    void creatingNeedsASignedInUser() throws Exception {
        mvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --- GET /posts/:id -----------------------------------------------------------------

    @Test
    void theAuthorSeesTheirPostWhileItIsProcessingAndEveryoneElseGets404() throws Exception {
        TestUser author = member(football);
        UUID postId = createdPost(author, football, Map.of());
        clock.set(START.plus(Duration.ofMinutes(40)));

        getPost(author, postId).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(postId.toString()))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.timeAgo").value("40m"))
                .andExpect(jsonPath("$.mediaUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Signature=")));
        expectError(getPost(member(football), postId), 404, "POST_NOT_FOUND");
    }

    @Test
    void aPublishedPostIsVisibleToEveryoneWithItsFinalMediaAndPoster() throws Exception {
        TestUser author = member(football);
        UUID postId = createdPost(author, football, Map.of());
        setStatus(postId, "published");
        jdbc.update("update media set final_key = 'media/final.mp4', poster_key = 'media/poster.jpg', status = 'ready'");

        getPost(newUser(), postId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.mediaUrl").value(org.hamcrest.Matchers.containsString("/media/media/final.mp4?")))
                .andExpect(jsonPath("$.posterUrl").value(org.hamcrest.Matchers.containsString("/media/media/poster.jpg?")));
    }

    @Test
    void aFailedPostIsVisibleOnlyToItsAuthor() throws Exception {
        TestUser author = member(football);
        UUID postId = createdPost(author, football, Map.of());
        setStatus(postId, "failed");

        getPost(author, postId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed"));
        expectError(getPost(newUser(), postId), 404, "POST_NOT_FOUND");
    }

    @Test
    void deletedAndRemovedPostsAreGoneEvenForTheAuthor() throws Exception {
        TestUser author = member(football);
        UUID deleted = createdPost(author, football, Map.of());
        setStatus(deleted, "deleted");
        UUID removed = createdPost(author, football, Map.of());
        setStatus(removed, "removed");

        expectError(getPost(author, deleted), 404, "POST_NOT_FOUND");
        expectError(getPost(author, removed), 404, "POST_NOT_FOUND");
    }

    @Test
    void aNicheMembersPostIsHiddenFromNonMembers() throws Exception {
        TestUser author = member(football);
        UUID postId = createdPost(author, football, Map.of("audience", "niche_members"));
        setStatus(postId, "published");

        getPost(member(football), postId).andExpect(status().isOk());
        expectError(getPost(member(cooking), postId), 404, "POST_NOT_FOUND");
    }

    @Test
    void aBlockEitherWayHidesThePost() throws Exception {
        TestUser author = member(football);
        TestUser viewer = newUser();
        UUID postId = createdPost(author, football, Map.of());
        setStatus(postId, "published");
        when(blocks.isBlockedEitherWay(viewer.id(), author.id())).thenReturn(true);

        expectError(getPost(viewer, postId), 404, "POST_NOT_FOUND");
        getPost(newUser(), postId).andExpect(status().isOk());
    }

    @Test
    void anUnknownPostIs404AndAMalformedIdIs400() throws Exception {
        TestUser viewer = newUser();
        expectError(getPost(viewer, UUID.randomUUID()), 404, "POST_NOT_FOUND");
        mvc.perform(get("/api/v1/posts/not-a-uuid").header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readingNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/posts/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
