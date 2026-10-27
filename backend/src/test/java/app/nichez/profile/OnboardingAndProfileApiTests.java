package app.nichez.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.support.ApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Sign-up and profiles: /me, /auth/session, the niche taxonomy and picks, suggested creators,
 * following, followers lists, profiles by handle or id, editing, photos, and a person's posts.
 */
class OnboardingAndProfileApiTests extends ApiTest {

    private List<UUID> ids(String... slugs) {
        List<UUID> result = new ArrayList<>();
        for (String slug : slugs) {
            result.add(niche(slug));
        }
        return result;
    }

    // --- /me and the session -------------------------------------------------------------

    @Test
    void meOfSomeoneWhoJustSignedInIsBlankAndNotOnboarded() throws Exception {
        User me = newcomer();

        get(me, "/api/v1/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value(me.id().toString()))
                .andExpect(jsonPath("$.profile.handle").doesNotExist())
                .andExpect(jsonPath("$.profile.isYou").value(true))
                .andExpect(jsonPath("$.profile.followers").value(0))
                .andExpect(jsonPath("$.onboarding.hasHandle").value(false))
                .andExpect(jsonPath("$.onboarding.hasNiches").value(false))
                .andExpect(jsonPath("$.onboarding.hasPhoto").value(false))
                .andExpect(jsonPath("$.onboarding.onboarded").value(false));
    }

    @Test
    void sessionTellsTheAppWhereToLand() throws Exception {
        User me = newcomer();
        get(me, "/api/v1/auth/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(me.id().toString()))
                .andExpect(jsonPath("$.hasHandle").value(false))
                .andExpect(jsonPath("$.onboarded").value(false))
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andExpect(jsonPath("$.reactivated").value(false));

        get(admin(), "/api/v1/auth/session").andExpect(jsonPath("$.isAdmin").value(true));
    }

    @Test
    void userMetadataCannotMakeAnAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into auth.users (id) values (?)", id);
        String token = app.nichez.security.TestTokens.valid().subject(id.toString())
                .userMetadata(java.util.Map.of("role", "admin")).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.isAdmin").value(false));
    }

    @Test
    void everythingNeedsASignedInUser() throws Exception {
        get(null, "/api/v1/me").andExpect(status().isUnauthorized());
        get(null, "/api/v1/niches/taxonomy").andExpect(status().isUnauthorized());
        get(null, "/api/v1/creators/suggested").andExpect(status().isUnauthorized());
    }

    // --- taxonomy and picks --------------------------------------------------------------

    @Test
    void taxonomyListsTheGroupsInOrderWithTheStrictFlag() throws Exception {
        get(newcomer(), "/api/v1/niches/taxonomy").andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].name").value("Religion"))
                .andExpect(jsonPath("$.items[0].strict").value(true))
                .andExpect(jsonPath("$.items[0].options.length()").value(3))
                .andExpect(jsonPath("$.items[1].name").value("Sport"))
                .andExpect(jsonPath("$.items[1].strict").value(false))
                .andExpect(jsonPath("$.items[3].options[0].label").value("Frontend"));
    }

    @Test
    void picksNeedThreeNichesAndOnlyOneFromAStrictGroup() throws Exception {
        User me = newcomer();

        expectError(put(me, "/api/v1/me/niches", body("nicheIds", ids("football"))), 400, "TOO_FEW_NICHES");
        expectError(put(me, "/api/v1/me/niches", body("nicheIds", ids("christianity", "islam", "football"))), 400,
                "ONE_NICHE_PER_GROUP");
        expectError(put(me, "/api/v1/me/niches", body("nicheIds", List.of(UUID.randomUUID(), niche("football"), niche("backend")))),
                404, "NICHE_NOT_FOUND");
        expectError(put(me, "/api/v1/me/niches", body("nicheIds", List.of())), 400, "VALIDATION_FAILED");

        put(me, "/api/v1/me/niches", body("nicheIds", ids("christianity", "football", "backend")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
        assertThat(count("select member_count from niches where slug = 'football'")).isEqualTo(1);
    }

    @Test
    void choosingAgainReplacesThePicksAndKeepsTheCounts() throws Exception {
        User me = newcomer();
        put(me, "/api/v1/me/niches", body("nicheIds", ids("football", "basketball", "backend"))).andExpect(status().isOk());
        put(me, "/api/v1/me/niches", body("nicheIds", ids("football", "basketball", "devops"))).andExpect(status().isOk());

        get(me, "/api/v1/me/niches").andExpect(jsonPath("$.items[*].slug", org.hamcrest.Matchers.containsInAnyOrder(
                "football", "basketball", "devops")));
        assertThat(count("select member_count from niches where slug = 'backend'")).isZero();
        assertThat(count("select member_count from niches where slug = 'devops'")).isEqualTo(1);
    }

    @Test
    void picksAndAHandleTogetherFinishOnboarding() throws Exception {
        User me = newcomer();
        put(me, "/api/v1/me/niches", body("nicheIds", ids("football", "basketball", "backend"))).andExpect(status().isOk());
        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.hasNiches").value(true))
                .andExpect(jsonPath("$.onboarded").value(false));

        put(me, "/api/v1/me/handle", body("handle", "ada_lovelace")).andExpect(status().isOk());

        get(me, "/api/v1/auth/session").andExpect(jsonPath("$.hasHandle").value(true))
                .andExpect(jsonPath("$.onboarded").value(true));
    }

    @Test
    void joiningAndLeavingANicheMovesTheCount() throws Exception {
        User me = user();
        UUID football = niche("football");

        post(me, "/api/v1/niches/" + football + "/join", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(true)).andExpect(jsonPath("$.members").value(1));
        post(me, "/api/v1/niches/" + football + "/join", null).andExpect(jsonPath("$.members").value(1));
        delete(me, "/api/v1/niches/" + football + "/join").andExpect(jsonPath("$.joined").value(false))
                .andExpect(jsonPath("$.members").value(0));
        expectError(post(me, "/api/v1/niches/" + UUID.randomUUID() + "/join", null), 404, "NICHE_NOT_FOUND");
    }

    // --- suggested creators and following -------------------------------------------------

    @Test
    void suggestedCreatorsShareNichesAndLeaveOutPeopleAlreadyFollowed() throws Exception {
        User me = user();
        join(me, "football");
        join(me, "basketball");
        User sharesTwo = user();
        join(sharesTwo, "football");
        join(sharesTwo, "basketball");
        User sharesNone = user();
        User alreadyFollowed = user();
        join(alreadyFollowed, "football");
        follow(me, alreadyFollowed);

        JsonNode items = read(get(me, "/api/v1/creators/suggested").andExpect(status().isOk())).get("items");

        assertThat(items.get(0).get("id").asText()).isEqualTo(sharesTwo.id().toString());
        assertThat(items.get(0).get("niche").asText()).isNotBlank();
        List<String> found = new ArrayList<>();
        items.forEach(i -> found.add(i.get("id").asText()));
        assertThat(found).contains(sharesNone.id().toString())
                .doesNotContain(me.id().toString(), alreadyFollowed.id().toString());
    }

    @Test
    void followAndUnfollowKeepTheCountsAndAreIdempotent() throws Exception {
        User a = user();
        User b = user();

        post(a, "/api/v1/users/" + b.id() + "/follow", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.isFollowing").value(true)).andExpect(jsonPath("$.followers").value(1));
        post(a, "/api/v1/users/" + b.id() + "/follow", null).andExpect(jsonPath("$.followers").value(1));
        get(a, "/api/v1/users/" + b.handle()).andExpect(jsonPath("$.followers").value(1))
                .andExpect(jsonPath("$.isFollowing").value(true));
        get(b, "/api/v1/users/" + a.handle()).andExpect(jsonPath("$.following").value(1))
                .andExpect(jsonPath("$.isFollowing").value(false));

        delete(a, "/api/v1/users/" + b.id() + "/follow").andExpect(jsonPath("$.isFollowing").value(false))
                .andExpect(jsonPath("$.followers").value(0));
        delete(a, "/api/v1/users/" + b.id() + "/follow").andExpect(jsonPath("$.followers").value(0));
    }

    @Test
    void followingYourselfAnUnknownPersonOrSomeoneBlockedIsRefused() throws Exception {
        User a = user();
        User b = user();
        expectError(post(a, "/api/v1/users/" + a.id() + "/follow", null), 400, "CANNOT_FOLLOW_SELF");
        expectError(post(a, "/api/v1/users/" + UUID.randomUUID() + "/follow", null), 404, "USER_NOT_FOUND");

        block(b, a);
        expectError(post(a, "/api/v1/users/" + b.id() + "/follow", null), 404, "USER_NOT_FOUND");
        expectError(post(b, "/api/v1/users/" + a.id() + "/follow", null), 404, "USER_NOT_FOUND");
        assertThat(count("select count(*) from follows")).isZero();
    }

    @Test
    void followingManyAtOnceSkipsWhoeverCannotBeFollowed() throws Exception {
        User me = user();
        User b = user();
        User c = user();
        User blocked = user();
        block(blocked, me);

        post(me, "/api/v1/me/follows", body("userIds", List.of(b.id(), c.id(), me.id(), UUID.randomUUID(), blocked.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.followed").value(2));
        assertThat(count("select following_count from profiles where id = ?", me.id())).isEqualTo(2);

        expectError(post(me, "/api/v1/me/follows", body("userIds", List.of())), 400, "VALIDATION_FAILED");
    }

    @Test
    void followersFollowingAndMutualListsSearchAndPage() throws Exception {
        User me = user("meg");
        User ann = user("ann_k");
        User bo = user("bo_k");
        User cy = user("cy");
        follow(ann, me);
        follow(bo, me);
        follow(me, bo);
        follow(me, cy);

        JsonNode followers = read(get(me, "/api/v1/users/" + me.id() + "/followers").andExpect(status().isOk()));
        assertThat(followers.get("items").size()).isEqualTo(2);
        assertThat(followers.get("nextCursor").isNull()).isTrue();

        get(me, "/api/v1/users/" + me.id() + "/followers?filter=following").andExpect(jsonPath("$.items.length()").value(2));
        get(me, "/api/v1/users/" + me.id() + "/followers?filter=mutual").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].handle").value("bo_k")).andExpect(jsonPath("$.items[0].isFollowing").value(true));
        get(me, "/api/v1/users/" + me.id() + "/followers?q=ann").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].handle").value("ann_k"));
        // A "%" typed in the search box is an ordinary character.
        get(me, "/api/v1/users/" + me.id() + "/followers?q=%25").andExpect(jsonPath("$.items.length()").value(0));

        JsonNode first = read(get(me, "/api/v1/users/" + me.id() + "/followers?limit=1"));
        assertThat(first.get("items").size()).isEqualTo(1);
        String cursor = first.get("nextCursor").asText();
        JsonNode second = read(get(me, "/api/v1/users/" + me.id() + "/followers?limit=1&cursor=" + cursor));
        assertThat(second.get("items").size()).isEqualTo(1);
        assertThat(second.get("items").get(0).get("id").asText()).isNotEqualTo(first.get("items").get(0).get("id").asText());

        expectError(get(me, "/api/v1/users/" + me.id() + "/followers?filter=bogus"), 400, "INVALID_FILTER");
        expectError(get(me, "/api/v1/users/" + me.id() + "/followers?cursor=not-a-cursor"), 400, "INVALID_CURSOR");
    }

    // --- reading profiles ----------------------------------------------------------------

    @Test
    void aProfileOpensByHandleWithOrWithoutAtAndById() throws Exception {
        User me = user();
        User ada = user("ada_l");
        join(ada, "football");
        join(ada, "backend");

        for (String key : List.of("ada_l", "@ada_l", "ADA_L", ada.id().toString())) {
            get(me, "/api/v1/users/" + key).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ada.id().toString()))
                    .andExpect(jsonPath("$.handle").value("ada_l"))
                    .andExpect(jsonPath("$.isYou").value(false))
                    .andExpect(jsonPath("$.niches.length()").value(2));
        }
        get(ada, "/api/v1/users/ada_l").andExpect(jsonPath("$.isYou").value(true));
        expectError(get(me, "/api/v1/users/nobody_here"), 404, "USER_NOT_FOUND");
    }

    @Test
    void aProfileNeverShowsAPhoneNumber() throws Exception {
        User me = user();
        User ada = user("ada_p");
        assertNoLeak(get(me, "/api/v1/users/" + ada.handle()).andExpect(status().isOk()), "phone", "+234");
    }

    @Test
    void blockedDeactivatedAndHandlelessProfilesAreAllTheSame404() throws Exception {
        User me = user();
        User blocker = user("blocks_me");
        block(blocker, me);
        User gone = user("gone_dev");
        jdbc.update("update profiles set deactivated_at = now() where id = ?", gone.id());
        User noHandle = newcomer();
        jdbc.update("insert into profiles (id) values (?)", noHandle.id());

        expectError(get(me, "/api/v1/users/blocks_me"), 404, "USER_NOT_FOUND");
        expectError(get(blocker, "/api/v1/users/" + me.handle()), 404, "USER_NOT_FOUND");
        expectError(get(me, "/api/v1/users/gone_dev"), 404, "USER_NOT_FOUND");
        expectError(get(me, "/api/v1/users/" + noHandle.id()), 404, "USER_NOT_FOUND");
    }

    // --- editing -------------------------------------------------------------------------

    @Test
    void editingNameBioAndHandle() throws Exception {
        User me = user("before_h");

        patch(me, "/api/v1/me", body("name", "  New Name ", "bio", "hello there")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name")).andExpect(jsonPath("$.bio").value("hello there"));
        put(me, "/api/v1/me/profile", body("bio", "")).andExpect(jsonPath("$.bio").value(""))
                .andExpect(jsonPath("$.name").value("New Name"));
        patch(me, "/api/v1/me", body("handle", "after_h")).andExpect(jsonPath("$.handle").value("after_h"));
        get(me, "/api/v1/users/before_h").andExpect(status().isNotFound());
    }

    @Test
    void editingRefusesBadInput() throws Exception {
        User me = user();
        User other = user("taken_h");

        expectError(patch(me, "/api/v1/me", body()), 400, "NOTHING_TO_UPDATE");
        expectError(patch(me, "/api/v1/me", body("name", "   ")), 400, "VALIDATION_FAILED");
        expectError(patch(me, "/api/v1/me", body("bio", "x".repeat(301))), 400, "VALIDATION_FAILED");
        expectError(patch(me, "/api/v1/me", body("handle", "a")), 400, "HANDLE_INVALID");
        expectError(patch(me, "/api/v1/me", body("handle", "admin")), 400, "HANDLE_RESERVED");
        expectError(patch(me, "/api/v1/me", body("handle", other.handle())), 409, "HANDLE_TAKEN");
    }

    @Test
    void aProfilePhotoIsSetFromAnUploadAndCanBeRemoved() throws Exception {
        User me = user();
        UUID media = pendingImage(me);

        post(me, "/api/v1/me/avatar", body("mediaId", media)).andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty());
        get(me, "/api/v1/me").andExpect(jsonPath("$.onboarding.hasPhoto").value(true));
        assertThat(jdbc.queryForObject("select status from media where id = ?", String.class, media)).isEqualTo("ready");

        delete(me, "/api/v1/me/avatar").andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
        get(me, "/api/v1/me").andExpect(jsonPath("$.onboarding.hasPhoto").value(false));
    }

    @Test
    void aPhotoUploadCannotBeReusedOrBorrowedOrFaked() throws Exception {
        User me = user();
        User other = user();
        UUID media = pendingImage(me);
        post(me, "/api/v1/me/avatar", body("mediaId", media)).andExpect(status().isOk());

        expectError(post(me, "/api/v1/me/avatar", body("mediaId", media)), 409, "MEDIA_ALREADY_USED");
        expectError(post(other, "/api/v1/me/avatar", body("mediaId", media)), 404, "MEDIA_NOT_FOUND");
        expectError(post(me, "/api/v1/me/avatar", body("mediaId", UUID.randomUUID())), 404, "MEDIA_NOT_FOUND");

        UUID video = UUID.randomUUID();
        jdbc.update("insert into media (id, owner_id, kind, declared_type, original_key, status) values (?, ?, 'video', 'video/mp4', ?, 'pending')",
                video, me.id(), "uploads/v/" + video);
        expectError(post(me, "/api/v1/me/avatar", body("mediaId", video)), 400, "UNSUPPORTED_MEDIA_TYPE");

        UUID missing = pendingImage(me);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        expectError(post(me, "/api/v1/me/avatar", body("mediaId", missing)), 409, "MEDIA_NOT_UPLOADED");

        stored(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1}, 1000);
        expectError(post(me, "/api/v1/me/avatar", body("mediaId", missing)), 400, "MEDIA_TYPE_MISMATCH");
    }

    @Test
    void aCoverIsSetAndRemovedLikeAPhoto() throws Exception {
        User me = user();
        put(me, "/api/v1/me/cover", body("mediaId", pendingImage(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.coverUrl").isNotEmpty());
        delete(me, "/api/v1/me/cover").andExpect(jsonPath("$.coverUrl").doesNotExist());
    }

    // --- a person's posts -----------------------------------------------------------------

    @Test
    void aPersonsPostsComeNewestFirstWithTabsAndPaging() throws Exception {
        User me = user();
        User author = user();
        join(me, "football");
        UUID older = publishedPost(author, "football", "older", 300);
        UUID newer = publishedPost(author, "football", "newer", 100);
        jdbc.update("update media set kind = 'video' where id = (select media_id from posts where id = ?)", older);

        JsonNode all = read(get(me, "/api/v1/users/" + author.id() + "/posts").andExpect(status().isOk()));
        assertThat(all.get("items").get(0).get("id").asText()).isEqualTo(newer.toString());
        assertThat(all.get("items").get(1).get("id").asText()).isEqualTo(older.toString());
        assertThat(all.get("items").get(0).get("isMine").asBoolean()).isFalse();

        get(me, "/api/v1/users/" + author.id() + "/posts?tab=media").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(older.toString())).andExpect(jsonPath("$.items[0].isVideo").value(true));

        JsonNode first = read(get(me, "/api/v1/users/" + author.id() + "/posts?limit=1"));
        String cursor = first.get("nextCursor").asText();
        get(me, "/api/v1/users/" + author.id() + "/posts?limit=1&cursor=" + cursor)
                .andExpect(jsonPath("$.items[0].id").value(older.toString())).andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void savedPostsAreOnlyForTheirOwnerAndOtherTabsAreChecked() throws Exception {
        User me = user();
        User author = user();
        join(me, "football");
        UUID post = publishedPost(author, "football", "keeper");
        putEmpty(me, "/api/v1/posts/" + post + "/save").andExpect(status().isOk());

        get(me, "/api/v1/users/" + me.id() + "/posts?tab=saved").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].saved").value(true));
        expectError(get(author, "/api/v1/users/" + me.id() + "/posts?tab=saved"), 403, "ACCESS_DENIED");
        expectError(get(me, "/api/v1/users/" + author.id() + "/posts?tab=bogus"), 400, "INVALID_TAB");
        expectError(get(me, "/api/v1/users/" + UUID.randomUUID() + "/posts"), 404, "USER_NOT_FOUND");
    }

    @Test
    void hiddenPostsDoNotShowOnAProfile() throws Exception {
        User viewer = user();
        User author = user();
        join(author, "football");
        publishedPost(author, "football", "visible");
        UUID nicheOnly = publishedPost(author, "football", "members only");
        jdbc.update("update posts set audience = 'niche_members' where id = ?", nicheOnly);
        UUID processing = publishedPost(author, "football", "still processing");
        jdbc.update("update posts set status = 'processing', published_at = null where id = ?", processing);

        get(viewer, "/api/v1/users/" + author.id() + "/posts").andExpect(jsonPath("$.items.length()").value(1));
        join(viewer, "football");
        get(viewer, "/api/v1/users/" + author.id() + "/posts").andExpect(jsonPath("$.items.length()").value(2));
    }
}
