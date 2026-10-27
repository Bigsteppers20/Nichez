package app.nichez.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.TestPostgres;
import app.nichez.auth.MutableClock;
import app.nichez.security.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * The base of the API integration tests: the real filter chain with locally signed tokens, a real
 * database with every migration applied, a clock the test can move, and a mocked object store. Every
 * subclass shares one Spring context, so the suite pays for startup once. Each test starts from empty
 * user, niche-membership, post and community tables.
 */
@SpringBootTest(properties = {
        "auth.mode=HS256",
        "auth.jwt-secret=" + TestTokens.SECRET,
        "auth.issuer=" + TestTokens.ISSUER
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTest.ClockConfig.class)
public abstract class ApiTest {

    protected static final Instant START = Instant.parse("2026-10-25T09:00:00Z");

    protected static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock apiTestClock() {
            return new MutableClock(START);
        }
    }

    /** A signed-in person: their id, their token and the handle they claimed. */
    protected record User(UUID id, String token, String handle) {
    }

    @MockitoBean
    protected S3Client s3;
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void resetApiTest() {
        clock.set(START);
        reset(s3);
        jdbc.execute("truncate table profiles, media, posts, user_niches, follows, niche_moderators, communities, bans cascade");
        jdbc.execute("delete from niches where not is_system");
        stored(PNG, 1000);
    }

    // --- people ------------------------------------------------------------------------

    protected User user() {
        // Digits only, so a generated handle can never contain a word a test searches for.
        return user("u" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L));
    }

    protected User user(String handle) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id, handle, name, onboarded_at) values (?, ?, ?, now())", id, handle,
                "Name of " + handle);
        return new User(id, TestTokens.valid().subject(id.toString()).build(), handle);
    }

    /** A person with a token but no profile row yet: signed in and nothing else. */
    protected User newcomer() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        return new User(id, TestTokens.valid().subject(id.toString()).build(), null);
    }

    protected User admin() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        jdbc.update("insert into profiles (id, handle, name) values (?, ?, ?)", id, "admin" + id.toString().substring(0, 6), "Admin");
        return new User(id, TestTokens.valid().subject(id.toString())
                .appMetadata(Map.of("provider", "phone", "role", "admin")).build(), null);
    }

    protected UUID niche(String slug) {
        return jdbc.queryForObject("select id from niches where slug = ?", UUID.class, slug);
    }

    protected void join(User user, String slug) {
        jdbc.update("insert into user_niches (user_id, niche_id) values (?, ?) on conflict do nothing", user.id(), niche(slug));
    }

    protected void follow(User follower, User followee) {
        jdbc.update("insert into follows (follower_id, followee_id) values (?, ?)", follower.id(), followee.id());
    }

    protected void block(User blocker, User blocked) {
        jdbc.update("insert into blocks (blocker_id, blocked_id) values (?, ?)", blocker.id(), blocked.id());
    }

    protected void makeModerator(User user, String slug) {
        jdbc.update("insert into niche_moderators (niche_id, user_id) values (?, ?)", niche(slug), user.id());
    }

    // --- content -----------------------------------------------------------------------

    /** A published image post, made {@code ageSeconds} before the test clock. */
    protected UUID publishedPost(User author, String slug, String caption, long ageSeconds) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media (id, owner_id, kind, declared_type, original_key, final_key, status)
                values (?, ?, 'image', 'image/png', ?, ?, 'ready')""", mediaId, author.id(), "uploads/" + mediaId,
                "media/" + mediaId);
        UUID postId = UUID.randomUUID();
        Timestamp at = Timestamp.from(clock.instant().minusSeconds(ageSeconds));
        jdbc.update("""
                insert into posts (id, author_id, niche_id, media_id, caption, status, created_at, published_at)
                values (?, ?, ?, ?, ?, 'published', ?, ?)""", postId, author.id(), niche(slug), mediaId, caption, at, at);
        return postId;
    }

    protected UUID publishedPost(User author, String slug, String caption) {
        return publishedPost(author, slug, caption, 60);
    }

    /** Storage answers as if a file whose first bytes are {@code content}, {@code size} bytes long, is stored. */
    protected void stored(byte[] content, long size) {
        // doReturn, not when(): the mock may currently be set to throw, and when() would call it.
        doReturn(HeadObjectResponse.builder().contentLength(size).build()).when(s3).headObject(any(HeadObjectRequest.class));
        doReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content)).when(s3)
                .getObjectAsBytes(any(GetObjectRequest.class));
    }

    /** An image the person has uploaded to storage: a pending media row they own. */
    protected UUID pendingImage(User owner) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media (id, owner_id, kind, declared_type, original_key, status)
                values (?, ?, 'image', 'image/png', ?, 'pending')""", mediaId, owner.id(), "uploads/" + owner.id() + "/" + mediaId);
        return mediaId;
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // --- requests ----------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, User user) {
        return user == null ? builder : builder.header("Authorization", "Bearer " + user.token());
    }

    protected ResultActions get(User user, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.get(path), user));
    }

    protected ResultActions post(User user, String path, Object body) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.post(path), user).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions postEmpty(User user, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.post(path), user));
    }

    protected ResultActions put(User user, String path, Object body) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.put(path), user).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions putEmpty(User user, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.put(path), user));
    }

    protected ResultActions patch(User user, String path, Object body) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.patch(path), user).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions delete(User user, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.delete(path), user));
    }

    protected static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** The standard error format: status, stable code and a request id. */
    protected static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(read(result).get("id").asText());
    }

    protected static void assertNoLeak(ResultActions result, String... forbidden) throws Exception {
        String text = result.andReturn().getResponse().getContentAsString();
        for (String value : forbidden) {
            assertThat(text).doesNotContain(value);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
