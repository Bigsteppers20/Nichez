package app.nichez.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.support.ApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The home feed, a niche's feed, post detail, and reactions to posts. */
class FeedAndReactionsApiTests extends ApiTest {

    private List<String> feedIds(User viewer, String path) throws Exception {
        List<String> ids = new ArrayList<>();
        read(get(viewer, path).andExpect(status().isOk())).get("items").forEach(p -> ids.add(p.get("id").asText()));
        return ids;
    }

    // --- the home feed -------------------------------------------------------------------

    @Test
    void theFeedHoldsMyNichesThePeopleIFollowAndMyOwnPostsNewestFirst() throws Exception {
        User me = user();
        join(me, "football");
        User fan = user();
        User stranger = user();
        User friend = user();
        follow(me, friend);
        UUID inMyNiche = publishedPost(fan, "football", "a", 300);
        publishedPost(stranger, "basketball", "not for me", 250);
        UUID fromFriend = publishedPost(friend, "basketball", "c", 200);
        UUID mine = publishedPost(me, "football", "d", 100);

        assertThat(feedIds(me, "/api/v1/feed")).containsExactly(mine.toString(), fromFriend.toString(), inMyNiche.toString());
    }

    @Test
    void theFeedPagesWithACursor() throws Exception {
        User me = user();
        join(me, "football");
        User author = user();
        UUID oldest = publishedPost(author, "football", "1", 300);
        UUID middle = publishedPost(author, "football", "2", 200);
        UUID newest = publishedPost(author, "football", "3", 100);

        JsonNode first = read(get(me, "/api/v1/feed?limit=2").andExpect(status().isOk()));
        assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(newest.toString());
        assertThat(first.get("items").get(1).get("id").asText()).isEqualTo(middle.toString());
        String cursor = first.get("nextCursor").asText();

        get(me, "/api/v1/feed?limit=2&cursor=" + cursor).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        expectError(get(me, "/api/v1/feed?cursor=bogus"), 400, "INVALID_CURSOR");
    }

    @Test
    void aFeedCardCarriesEverythingTheScreenShowsAndTheViewersOwnState() throws Exception {
        User me = user();
        join(me, "football");
        User author = user("card_author");
        UUID post = publishedPost(author, "football", "spent 3 hours on a pivot table", 60);
        put(me, "/api/v1/posts/" + post + "/reaction", body("kind", "fire")).andExpect(status().isOk());
        putEmpty(me, "/api/v1/posts/" + post + "/save").andExpect(status().isOk());

        get(me, "/api/v1/feed").andExpect(jsonPath("$.items[0].id").value(post.toString()))
                .andExpect(jsonPath("$.items[0].author.handle").value("card_author"))
                .andExpect(jsonPath("$.items[0].author.name").value("Name of card_author"))
                .andExpect(jsonPath("$.items[0].niche").value("Football"))
                .andExpect(jsonPath("$.items[0].caption").value("spent 3 hours on a pivot table"))
                .andExpect(jsonPath("$.items[0].timeAgo").value("1m"))
                .andExpect(jsonPath("$.items[0].isVideo").value(false))
                .andExpect(jsonPath("$.items[0].mediaUrl").isNotEmpty())
                .andExpect(jsonPath("$.items[0].reactions").value(1))
                .andExpect(jsonPath("$.items[0].reactionCounts.fire").value(1))
                .andExpect(jsonPath("$.items[0].myReaction").value("fire"))
                .andExpect(jsonPath("$.items[0].saved").value(true))
                .andExpect(jsonPath("$.items[0].saves").value(1))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[0].sensitive").value(false));
        // The same post for someone else shows none of my state: it is computed from the session.
        User other = user();
        join(other, "football");
        get(other, "/api/v1/feed").andExpect(jsonPath("$.items[0].myReaction").doesNotExist())
                .andExpect(jsonPath("$.items[0].saved").value(false));
    }

    @Test
    void theFeedLeavesOutWhatTheViewerMutedBlockedHidReportedOrCannotSee() throws Exception {
        User me = user();
        join(me, "football");
        UUID shown = publishedPost(user(), "football", "shown");

        User muted = user();
        publishedPost(muted, "football", "muted author");
        jdbc.update("insert into mutes (muter_id, muted_id) values (?, ?)", me.id(), muted.id());
        User iBlocked = user();
        publishedPost(iBlocked, "football", "blocked by me");
        block(me, iBlocked);
        User blockedMe = user();
        publishedPost(blockedMe, "football", "blocked me");
        block(blockedMe, me);
        UUID notInterested = publishedPost(user(), "football", "not interested");
        postEmpty(me, "/api/v1/posts/" + notInterested + "/not-interested").andExpect(status().isNoContent());
        UUID reported = publishedPost(user(), "football", "reported");
        post(me, "/api/v1/posts/" + reported + "/report", body("reasonId", "spam")).andExpect(status().isCreated());
        User gone = user();
        publishedPost(gone, "football", "deactivated author");
        jdbc.update("update profiles set deactivated_at = now() where id = ?", gone.id());
        UUID processing = publishedPost(user(), "football", "processing");
        jdbc.update("update posts set status = 'processing', published_at = null where id = ?", processing);
        User follows = user();
        follow(me, follows);
        UUID nicheOnly = publishedPost(follows, "basketball", "members of basketball only");
        jdbc.update("update posts set audience = 'niche_members' where id = ?", nicheOnly);

        assertThat(feedIds(me, "/api/v1/feed")).containsExactly(shown.toString());
    }

    @Test
    void reportedPostsComeBackIfTheViewerTurnsAutoHideOff() throws Exception {
        User me = user();
        join(me, "football");
        UUID reported = publishedPost(user(), "football", "reported");
        post(me, "/api/v1/posts/" + reported + "/report", body("reasonId", "spam")).andExpect(status().isCreated());
        assertThat(feedIds(me, "/api/v1/feed")).isEmpty();

        put(me, "/api/v1/me/preferences/auto-hide-reported", body("enabled", false)).andExpect(status().isOk());
        assertThat(feedIds(me, "/api/v1/feed")).containsExactly(reported.toString());
    }

    @Test
    void sensitivePostsAreVeiledHiddenOrShownAsThePersonChose() throws Exception {
        User me = user();
        join(me, "football");
        UUID post = publishedPost(user(), "football", "graphic");
        jdbc.update("update posts set sensitive = true, sensitive_reason = 'Graphic injury clip' where id = ?", post);

        get(me, "/api/v1/feed").andExpect(jsonPath("$.items[0].sensitive").value(true))
                .andExpect(jsonPath("$.items[0].sensitiveReason").value("Graphic injury clip"));
        patch(me, "/api/v1/me/content-preferences", body("sensitiveFilter", "hide")).andExpect(status().isOk());
        assertThat(feedIds(me, "/api/v1/feed")).isEmpty();
        patch(me, "/api/v1/me/content-preferences", body("sensitiveFilter", "show")).andExpect(status().isOk());
        assertThat(feedIds(me, "/api/v1/feed")).containsExactly(post.toString());
    }

    @Test
    void aPostSomeoneIFollowResharedShowsUpWithWhoSharedIt() throws Exception {
        User me = user();
        User friend = user("sharer_f");
        follow(me, friend);
        User stranger = user();
        UUID post = publishedPost(stranger, "basketball", "worth sharing");
        assertThat(feedIds(me, "/api/v1/feed")).isEmpty();

        post(friend, "/api/v1/posts/" + post + "/reshares", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.reshared").value(true)).andExpect(jsonPath("$.reshares").value(1));

        get(me, "/api/v1/feed").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(post.toString()))
                .andExpect(jsonPath("$.items[0].resharedBy.handle").value("sharer_f"))
                .andExpect(jsonPath("$.items[0].reshares").value(1));
    }

    @Test
    void aNichesFeedShowsThatNicheToAnyoneAndTheTabStripListsMyNiches() throws Exception {
        User me = user();
        join(me, "football");
        UUID basketball = publishedPost(user(), "basketball", "hoops");
        publishedPost(user(), "football", "goals");

        assertThat(feedIds(me, "/api/v1/feed?niche=" + niche("basketball"))).containsExactly(basketball.toString());
        assertThat(feedIds(me, "/api/v1/niches/" + niche("basketball") + "/feed")).containsExactly(basketball.toString());
        get(me, "/api/v1/niches/" + niche("football") + "/feed").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        expectError(get(me, "/api/v1/niches/" + UUID.randomUUID() + "/feed"), 404, "NICHE_NOT_FOUND");
        expectError(get(me, "/api/v1/feed?niche=" + UUID.randomUUID()), 404, "NICHE_NOT_FOUND");
        get(me, "/api/v1/feed/niches").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("football"));
    }

    @Test
    void anEmptyFeedIsAnEmptyListNotAnError() throws Exception {
        get(user(), "/api/v1/feed").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        get(null, "/api/v1/feed").andExpect(status().isUnauthorized());
    }

    // --- reactions -----------------------------------------------------------------------

    @Test
    void reactingSetsChangesAndClearsOneReactionPerPerson() throws Exception {
        User author = user();
        User me = user();
        User other = user();
        UUID post = publishedPost(author, "football", "react to me");
        String path = "/api/v1/posts/" + post + "/reaction";

        put(me, path, body("kind", "laugh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.myReaction").value("laugh")).andExpect(jsonPath("$.reactions").value(1));
        put(me, path, body("kind", "fire")).andExpect(jsonPath("$.myReaction").value("fire"))
                .andExpect(jsonPath("$.reactions").value(1));
        put(other, path, body("kind", "love")).andExpect(jsonPath("$.reactions").value(2));

        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.myReaction").value("fire"))
                .andExpect(jsonPath("$.reactionCounts.fire").value(1)).andExpect(jsonPath("$.reactionCounts.love").value(1))
                .andExpect(jsonPath("$.reactionCounts.laugh").doesNotExist());

        delete(me, path).andExpect(jsonPath("$.myReaction").doesNotExist()).andExpect(jsonPath("$.reactions").value(1));
        delete(me, path).andExpect(status().isOk()).andExpect(jsonPath("$.reactions").value(1));
    }

    @Test
    void reactingRefusesBadInputAndPostsThePersonCannotSee() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "x");
        String path = "/api/v1/posts/" + post + "/reaction";

        expectError(put(me, path, body("kind", "bogus")), 400, "VALIDATION_FAILED");
        expectError(put(me, path, body()), 400, "VALIDATION_FAILED");
        expectError(put(me, "/api/v1/posts/" + UUID.randomUUID() + "/reaction", body("kind", "love")), 404, "POST_NOT_FOUND");
        put(null, path, body("kind", "love")).andExpect(status().isUnauthorized());

        block(author, me);
        expectError(put(me, path, body("kind", "love")), 404, "POST_NOT_FOUND");
        assertThat(count("select count(*) from reactions")).isZero();
    }

    @Test
    void theAuthorIsToldOncePerPersonAndNeverAboutTheirOwnReaction() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "x");
        String path = "/api/v1/posts/" + post + "/reaction";

        put(me, path, body("kind", "laugh")).andExpect(status().isOk());
        put(me, path, body("kind", "fire")).andExpect(status().isOk());
        delete(me, path).andExpect(status().isOk());
        put(me, path, body("kind", "love")).andExpect(status().isOk());
        put(author, path, body("kind", "love")).andExpect(status().isOk());

        assertThat(count("select count(*) from notifications where user_id = ? and kind = 'reaction'", author.id())).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ?", me.id())).isZero();
    }
}
