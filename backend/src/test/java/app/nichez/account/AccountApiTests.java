package app.nichez.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.account.service.AccountErasureJob;
import app.nichez.support.ApiTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Settings and the account lifecycle: reading and changing settings, deactivating, asking for deletion
 * and cancelling it, the daily erasure, and asking for a copy of one's data.
 */
class AccountApiTests extends ApiTest {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final Duration GRACE = Duration.ofDays(14);

    @Autowired
    private AccountErasureJob erasure;

    private long audits(String eventType, User user) {
        return count("select count(*) from audit_events where event_type = ? and details->>'userId' = ?", eventType,
                user.id().toString());
    }

    // --- signed-in only ------------------------------------------------------------------

    @Test
    void everythingNeedsASignedInUser() throws Exception {
        get(null, SETTINGS).andExpect(status().isUnauthorized());
        patch(null, SETTINGS, body()).andExpect(status().isUnauthorized());
        postEmpty(null, "/api/v1/me/deactivate").andExpect(status().isUnauthorized());
        delete(null, "/api/v1/me").andExpect(status().isUnauthorized());
        postEmpty(null, "/api/v1/me/deletion/cancel").andExpect(status().isUnauthorized());
        postEmpty(null, "/api/v1/me/data-export").andExpect(status().isUnauthorized());
    }

    // --- settings ------------------------------------------------------------------------

    @Test
    void settingsStartAtTheDefaults() throws Exception {
        get(user(), SETTINGS).andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.pushReactions").value(true))
                .andExpect(jsonPath("$.notifications.pushComments").value(true))
                .andExpect(jsonPath("$.notifications.pushFollows").value(true))
                .andExpect(jsonPath("$.notifications.pushMentions").value(true))
                .andExpect(jsonPath("$.notifications.pushReshares").value(true))
                .andExpect(jsonPath("$.notifications.quietHoursEnabled").value(false))
                .andExpect(jsonPath("$.notifications.quietHoursStart").value("22:00"))
                .andExpect(jsonPath("$.notifications.quietHoursEnd").value("07:00"))
                .andExpect(jsonPath("$.content.sensitiveFilter").value("veil"))
                .andExpect(jsonPath("$.content.autoHideReported").value(true));
    }

    @Test
    void changingSettingsTouchesOnlyWhatIsSent() throws Exception {
        User me = user();

        patch(me, SETTINGS, body("notifications",
                body("pushReactions", false, "quietHoursEnabled", true, "quietHoursStart", "23:30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.pushReactions").value(false))
                .andExpect(jsonPath("$.notifications.pushComments").value(true))
                .andExpect(jsonPath("$.notifications.quietHoursEnabled").value(true))
                .andExpect(jsonPath("$.notifications.quietHoursStart").value("23:30"))
                .andExpect(jsonPath("$.notifications.quietHoursEnd").value("07:00"))
                .andExpect(jsonPath("$.content.sensitiveFilter").value("veil"));

        patch(me, SETTINGS, body("content", body("sensitiveFilter", "hide")))
                .andExpect(jsonPath("$.content.sensitiveFilter").value("hide"))
                .andExpect(jsonPath("$.content.autoHideReported").value(true))
                .andExpect(jsonPath("$.notifications.pushReactions").value(false));

        // Nothing sent, nothing changed.
        patch(me, SETTINGS, body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.sensitiveFilter").value("hide"))
                .andExpect(jsonPath("$.notifications.quietHoursStart").value("23:30"));

        get(me, SETTINGS).andExpect(jsonPath("$.notifications.pushReactions").value(false))
                .andExpect(jsonPath("$.content.sensitiveFilter").value("hide"));
    }

    @Test
    void settingsBelongToTheirOwnerAndBadValuesAreRefused() throws Exception {
        User me = user();
        User other = user();
        patch(me, SETTINGS, body("notifications", body("pushFollows", false))).andExpect(status().isOk());
        get(other, SETTINGS).andExpect(jsonPath("$.notifications.pushFollows").value(true));

        expectError(patch(me, SETTINGS, body("notifications", body("quietHoursStart", "25:00"))), 400,
                "VALIDATION_FAILED");
        expectError(patch(me, SETTINGS, body("content", body("sensitiveFilter", "bogus"))), 400, "VALIDATION_FAILED");
        get(me, SETTINGS).andExpect(jsonPath("$.content.sensitiveFilter").value("veil"));
    }

    @Test
    void settingsWorkForSomeoneWhoHasNoProfileYet() throws Exception {
        User me = newcomer();
        get(me, SETTINGS).andExpect(status().isOk()).andExpect(jsonPath("$.content.sensitiveFilter").value("veil"));

        patch(me, SETTINGS, body("notifications", body("pushComments", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.pushComments").value(false));
        patch(newcomer(), SETTINGS, body("content", body("autoHideReported", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.autoHideReported").value(false));
    }

    @Test
    void aPushTokenCanBeRegisteredBeforeAProfileExists() throws Exception {
        User me = newcomer();
        put(me, "/api/v1/me/push-token", body("token", "device-token-12345", "platform", "android"))
                .andExpect(status().isNoContent());
        assertThat(count("select count(*) from devices where user_id = ?", me.id())).isEqualTo(1);
    }

    // --- deactivating ----------------------------------------------------------------------

    @Test
    void deactivatingHidesThePersonUntilTheyNextSignIn() throws Exception {
        User me = user("hides_me");
        User other = user();
        join(me, "football");
        join(other, "football");
        UUID post = publishedPost(me, "football", "still here");
        get(other, "/api/v1/users/hides_me").andExpect(status().isOk());

        postEmpty(me, "/api/v1/me/deactivate").andExpect(status().isNoContent());

        expectError(get(other, "/api/v1/users/hides_me"), 404, "USER_NOT_FOUND");
        expectError(get(other, "/api/v1/posts/" + post), 404, "POST_NOT_FOUND");
        assertThat(audits("ACCOUNT_DEACTIVATED", me)).isEqualTo(1);

        // Signing in brings them back, once.
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.reactivated").value(true));
        get(other, "/api/v1/users/hides_me").andExpect(status().isOk());
        get(other, "/api/v1/posts/" + post).andExpect(status().isOk());
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.reactivated").value(false));
    }

    @Test
    void deactivatingTwiceIsHarmless() throws Exception {
        User me = user();
        postEmpty(me, "/api/v1/me/deactivate").andExpect(status().isNoContent());
        postEmpty(me, "/api/v1/me/deactivate").andExpect(status().isNoContent());
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.reactivated").value(true));
    }

    // --- asking for deletion ---------------------------------------------------------------

    @Test
    void deletingHidesThePersonAtOnceAndSchedulesTheErasure() throws Exception {
        User me = user("leaving");
        User other = user();

        delete(me, "/api/v1/me").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deletionScheduledFor").value(START.plus(GRACE).toString()));

        expectError(get(other, "/api/v1/users/leaving"), 404, "USER_NOT_FOUND");
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.deletionScheduledFor").value(START.plus(GRACE).toString()));
        assertThat(audits("ACCOUNT_DELETION_REQUESTED", me)).isEqualTo(1);
    }

    @Test
    void askingAgainKeepsTheFirstDate() throws Exception {
        User me = user();
        delete(me, "/api/v1/me").andExpect(status().isAccepted());

        clock.advance(Duration.ofHours(5));
        delete(me, "/api/v1/me").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deletionScheduledFor").value(START.plus(GRACE).toString()));
        assertThat(audits("ACCOUNT_DELETION_REQUESTED", me)).isEqualTo(1);
    }

    @Test
    void signingInDoesNotCancelADeletion() throws Exception {
        User me = user();
        delete(me, "/api/v1/me").andExpect(status().isAccepted());

        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.deletionScheduledFor").isNotEmpty());
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.deletionScheduledFor").isNotEmpty());
    }

    @Test
    void cancellingBringsThePersonBack() throws Exception {
        User me = user("changed_mind");
        User other = user();
        delete(me, "/api/v1/me").andExpect(status().isAccepted());

        postEmpty(me, "/api/v1/me/deletion/cancel").andExpect(status().isNoContent());

        get(other, "/api/v1/users/changed_mind").andExpect(status().isOk());
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.deletionScheduledFor").doesNotExist());
        assertThat(audits("ACCOUNT_DELETION_CANCELLED", me)).isEqualTo(1);

        // A cancelled request is gone for good: the daily job leaves them alone.
        clock.advance(GRACE.multipliedBy(2));
        assertThat(erasure.run()).isZero();
        get(other, "/api/v1/users/changed_mind").andExpect(status().isOk());
    }

    @Test
    void cancellingWithNothingToCancelIsRefused() throws Exception {
        expectError(postEmpty(user(), "/api/v1/me/deletion/cancel"), 409, "NO_DELETION_PENDING");
        expectError(postEmpty(newcomer(), "/api/v1/me/deletion/cancel"), 409, "NO_DELETION_PENDING");

        User me = user();
        delete(me, "/api/v1/me").andExpect(status().isAccepted());
        postEmpty(me, "/api/v1/me/deletion/cancel").andExpect(status().isNoContent());
        expectError(postEmpty(me, "/api/v1/me/deletion/cancel"), 409, "NO_DELETION_PENDING");
    }

    // --- the daily erasure -----------------------------------------------------------------

    @Test
    void theErasureWaitsForTheGracePeriodAndRunsOnce() throws Exception {
        User waiting = user();
        User untouched = user();
        delete(waiting, "/api/v1/me").andExpect(status().isAccepted());

        clock.advance(GRACE.minusSeconds(1));
        assertThat(erasure.run()).isZero();
        assertThat(count("select count(*) from profiles where deleted_at is not null")).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(erasure.run()).isEqualTo(1);
        assertThat(erasure.run()).isZero();
        assertThat(audits("ACCOUNT_ERASED", waiting)).isEqualTo(1);
        assertThat(count("select count(*) from profiles where id = ? and deleted_at is null and handle is not null",
                untouched.id())).isEqualTo(1);
    }

    @Test
    void erasingAPersonTakesDownWhatTheyMadeAndClearsTheirProfile() throws Exception {
        User gone = user("going_away");
        User friend = user("friend_x");
        join(gone, "football");
        join(friend, "football");
        UUID mine = publishedPost(gone, "football", "my post");
        UUID theirs = publishedPost(friend, "football", "a friend's post");
        put(gone, "/api/v1/posts/" + theirs + "/reaction", body("kind", "laugh")).andExpect(status().isOk());
        post(gone, "/api/v1/posts/" + theirs + "/comments", body("body", "nice one"))
                .andExpect(status().is2xxSuccessful());
        follow(gone, friend);
        follow(friend, gone);
        put(gone, "/api/v1/me/push-token", body("token", "device-token-12345", "platform", "android"))
                .andExpect(status().isNoContent());
        assertThat(count("select reaction_count from posts where id = ?", theirs)).isEqualTo(1);
        assertThat(count("select comment_count from posts where id = ?", theirs)).isEqualTo(1);
        jdbc.update("""
                insert into request_logs (created_at, request_id, method, route, status, duration_ms, user_id)
                values (now(), 'erase-check', 'GET', '/api/v1/me', 200, 3, ?)""", gone.id());

        delete(gone, "/api/v1/me").andExpect(status().isAccepted());
        clock.advance(GRACE);
        assertThat(erasure.run()).isEqualTo(1);

        // The request log keeps the call but no longer says whose it was.
        assertThat(count("select count(*) from request_logs where request_id = 'erase-check' and user_id is null"))
                .isEqualTo(1);
        assertThat(count("select count(*) from request_logs where user_id = ?", gone.id())).isZero();

        // Their posts come down, to be purged with the usual retention.
        Map<String, Object> post = jdbc.queryForMap("select status, deleted_at from posts where id = ?", mine);
        assertThat(post.get("status")).isEqualTo("deleted");
        assertThat(post.get("deleted_at")).isNotNull();
        expectError(get(friend, "/api/v1/posts/" + mine), 404, "POST_NOT_FOUND");

        // What they did to other people's posts goes, and the counters follow.
        assertThat(count("select reaction_count from posts where id = ?", theirs)).isZero();
        assertThat(count("select comment_count from posts where id = ?", theirs)).isZero();
        assertThat(count("select count(*) from follows where follower_id = ? or followee_id = ?", gone.id(), gone.id()))
                .isZero();
        assertThat(count("select follower_count + following_count from profiles where id = ?", friend.id())).isZero();
        assertThat(count("select count(*) from devices where user_id = ?", gone.id())).isZero();
        assertThat(count("select count(*) from user_niches where user_id = ?", gone.id())).isZero();

        // The profile is cleared and the handle can be claimed again.
        Map<String, Object> profile = jdbc.queryForMap(
                "select name, handle, bio, avatar_url, deleted_at from profiles where id = ?", gone.id());
        assertThat(profile.get("name")).isNull();
        assertThat(profile.get("handle")).isNull();
        assertThat(profile.get("bio")).isNull();
        assertThat(profile.get("avatar_url")).isNull();
        assertThat(profile.get("deleted_at")).isNotNull();
        expectError(get(friend, "/api/v1/users/" + gone.id()), 404, "USER_NOT_FOUND");
        put(newcomer(), "/api/v1/me/handle", body("handle", "going_away")).andExpect(status().isOk());

        // A token that has not expired yet stops working.
        expectError(get(gone, "/api/v1/me"), 403, "ACCOUNT_DELETED");
        expectError(get(gone, SETTINGS), 403, "ACCOUNT_DELETED");
        get(friend, "/api/v1/users/friend_x").andExpect(status().isOk());
    }

    @Test
    void aCommunityTheyFoundedPassesToAModeratorBeforeAnyMember() throws Exception {
        User founder = user();
        User moderator = user();
        User member = user();
        UUID community = community(founder, "Club");
        addMember(community, member, "member", "2026-01-01T00:00:00Z");
        addMember(community, moderator, "moderator", "2026-02-01T00:00:00Z");

        delete(founder, "/api/v1/me").andExpect(status().isAccepted());
        clock.advance(GRACE);
        assertThat(erasure.run()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select role from community_members where community_id = ? and user_id = ?",
                String.class, community, moderator.id())).isEqualTo("founder");
        assertThat(jdbc.queryForObject("select role from community_members where community_id = ? and user_id = ?",
                String.class, community, member.id())).isEqualTo("member");
        assertThat(count("select count(*) from communities where id = ? and deleted_at is null", community)).isEqualTo(1);
        assertThat(count("select member_count from communities where id = ?", community)).isEqualTo(2);
    }

    @Test
    void aCommunityNobodyElseIsInIsTakenDown() throws Exception {
        User founder = user();
        UUID community = community(founder, "Empty Club");

        delete(founder, "/api/v1/me").andExpect(status().isAccepted());
        clock.advance(GRACE);
        assertThat(erasure.run()).isEqualTo(1);

        assertThat(count("select count(*) from communities where id = ? and deleted_at is not null", community))
                .isEqualTo(1);
    }

    // --- a copy of one's data --------------------------------------------------------------

    @Test
    void askingForADataCopyIsRecordedOnceUntilItIsDone() throws Exception {
        User me = user();
        User other = user();

        postEmpty(me, "/api/v1/me/data-export").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("requested"));
        expectError(postEmpty(me, "/api/v1/me/data-export"), 409, "EXPORT_ALREADY_REQUESTED");
        postEmpty(other, "/api/v1/me/data-export").andExpect(status().isAccepted());
        assertThat(count("select count(*) from data_export_requests where user_id = ?", me.id())).isEqualTo(1);

        jdbc.update("update data_export_requests set status = 'ready', completed_at = now() where user_id = ?", me.id());
        postEmpty(me, "/api/v1/me/data-export").andExpect(status().isAccepted());
        assertThat(count("select count(*) from data_export_requests where user_id = ?", me.id())).isEqualTo(2);
    }

    @Test
    void aDataCopyCanBeRequestedBeforeAProfileExists() throws Exception {
        postEmpty(newcomer(), "/api/v1/me/data-export").andExpect(status().isAccepted());
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID community(User founder, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into communities (id, name, home_niche_id, created_by) values (?, ?, ?, ?)", id, name,
                niche("football"), founder.id());
        addMember(id, founder, "founder", "2026-01-01T00:00:00Z");
        return id;
    }

    private void addMember(UUID community, User user, String role, String joinedAt) {
        jdbc.update("""
                insert into community_members (community_id, user_id, role, joined_at)
                values (?, ?, ?, ?::timestamptz)""", community, user.id(), role, joinedAt);
    }
}
