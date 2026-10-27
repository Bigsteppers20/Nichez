package app.nichez.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.support.ApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Comments and replies, reactions to comments, saves, and collections. */
class CommentsAndSavesApiTests extends ApiTest {

    private UUID comment(User who, UUID post, String text, UUID parent) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return idOf(post(who, "/api/v1/posts/" + post + "/comments",
                parent == null ? body("body", text) : body("body", text, "parentId", parent)).andExpect(status().isCreated()));
    }

    // --- comments ------------------------------------------------------------------------

    @Test
    void aCommentComesBackShapedLikeTheApp() throws Exception {
        User author = user();
        User me = user("commenter");
        UUID post = publishedPost(author, "football", "x");

        post(me, "/api/v1/posts/" + post + "/comments", body("body", "  the 4 years part is too real.  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("the 4 years part is too real."))
                .andExpect(jsonPath("$.authorName").value("Name of commenter"))
                .andExpect(jsonPath("$.authorHandle").value("commenter"))
                .andExpect(jsonPath("$.timeAgo").value("now"))
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.isReply").value(false))
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.parentId").doesNotExist());
        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.comments").value(1));
    }

    @Test
    void repliesStayOneLevelDeepAndFollowTheirComment() throws Exception {
        User author = user();
        User me = user();
        User other = user();
        UUID post = publishedPost(author, "football", "x");
        UUID first = comment(me, post, "first", null);
        UUID reply = comment(other, post, "a reply", first);
        UUID replyToReply = comment(me, post, "reply to the reply", reply);
        UUID second = comment(other, post, "second", null);

        JsonNode items = read(get(me, "/api/v1/posts/" + post + "/comments?sort=new").andExpect(status().isOk())).get("items");
        assertThat(items.size()).isEqualTo(4);
        assertThat(items.get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(items.get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(items.get(2).get("id").asText()).isEqualTo(reply.toString());
        assertThat(items.get(2).get("isReply").asBoolean()).isTrue();
        assertThat(items.get(2).get("parentId").asText()).isEqualTo(first.toString());
        // A reply to a reply lands under the comment it answers.
        assertThat(items.get(3).get("id").asText()).isEqualTo(replyToReply.toString());
        assertThat(items.get(3).get("parentId").asText()).isEqualTo(first.toString());
    }

    @Test
    void topSortPutsTheMostReactedFirstAndNewSortPutsTheNewestFirst() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "x");
        UUID older = comment(me, post, "older", null);
        UUID newer = comment(me, post, "newer", null);

        putEmpty(author, "/api/v1/comments/" + older + "/reaction").andExpect(status().isOk())
                .andExpect(jsonPath("$.myReaction").value("love")).andExpect(jsonPath("$.score").value(1));

        get(me, "/api/v1/posts/" + post + "/comments?sort=top").andExpect(jsonPath("$.items[0].id").value(older.toString()))
                .andExpect(jsonPath("$.items[0].score").value(1)).andExpect(jsonPath("$.items[1].score").doesNotExist());
        get(me, "/api/v1/posts/" + post + "/comments").andExpect(jsonPath("$.items[0].id").value(older.toString()));
        get(me, "/api/v1/posts/" + post + "/comments?sort=new").andExpect(jsonPath("$.items[0].id").value(newer.toString()));
        get(author, "/api/v1/posts/" + post + "/comments").andExpect(jsonPath("$.items[0].myReaction").value("love"));
        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.topComment.id").value(older.toString()))
                .andExpect(jsonPath("$.topComment.score").value(1)).andExpect(jsonPath("$.topComment.body").value("older"));

        delete(author, "/api/v1/comments/" + older + "/reaction").andExpect(jsonPath("$.myReaction").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist());
        expectError(get(me, "/api/v1/posts/" + post + "/comments?sort=sideways"), 400, "INVALID_SORT");
    }

    @Test
    void commentPagesCountTopLevelCommentsAndCarryTheirReplies() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "x");
        UUID a = comment(me, post, "a", null);
        comment(author, post, "a-reply", a);
        UUID b = comment(me, post, "b", null);
        UUID c = comment(me, post, "c", null);

        JsonNode first = read(get(me, "/api/v1/posts/" + post + "/comments?sort=new&limit=2"));
        assertThat(first.get("items").size()).isEqualTo(2);
        assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(c.toString());
        String cursor = first.get("nextCursor").asText();
        JsonNode second = read(get(me, "/api/v1/posts/" + post + "/comments?sort=new&limit=2&cursor=" + cursor));
        // The second page is one top-level comment (a) and its reply: pages count top-level comments only.
        assertThat(second.get("items").size()).isEqualTo(2);
        assertThat(second.get("items").get(0).get("id").asText()).isEqualTo(a.toString());
        assertThat(second.get("items").get(1).get("isReply").asBoolean()).isTrue();
        assertThat(second.get("nextCursor").isNull()).isTrue();

        JsonNode top = read(get(me, "/api/v1/posts/" + post + "/comments?limit=1"));
        assertThat(top.get("nextCursor").isNull()).isFalse();
        get(me, "/api/v1/posts/" + post + "/comments?limit=1&cursor=" + top.get("nextCursor").asText()).andExpect(status().isOk());
    }

    @Test
    void deletingYourCommentTakesItsRepliesAndTheCountWithIt() throws Exception {
        User author = user();
        User me = user();
        User other = user();
        UUID post = publishedPost(author, "football", "x");
        UUID mine = comment(me, post, "mine", null);
        UUID reply = comment(other, post, "reply", mine);
        comment(other, post, "elsewhere", null);
        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.comments").value(3));

        expectError(delete(other, "/api/v1/comments/" + mine), 404, "COMMENT_NOT_FOUND");
        delete(me, "/api/v1/comments/" + mine).andExpect(status().isNoContent());

        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.comments").value(1));
        get(me, "/api/v1/posts/" + post + "/comments").andExpect(jsonPath("$.items.length()").value(1));
        expectError(delete(me, "/api/v1/comments/" + mine), 404, "COMMENT_NOT_FOUND");
        expectError(delete(other, "/api/v1/comments/" + reply), 404, "COMMENT_NOT_FOUND");
    }

    @Test
    void commentingRefusesBadInputAndPostsThatTurnedCommentsOff() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "x");
        String path = "/api/v1/posts/" + post + "/comments";

        expectError(post(me, path, body("body", "   ")), 400, "VALIDATION_FAILED");
        expectError(post(me, path, body("body", "x".repeat(1001))), 400, "VALIDATION_FAILED");
        expectError(post(me, path, body()), 400, "VALIDATION_FAILED");
        expectError(post(me, path, body("body", "hi", "parentId", UUID.randomUUID())), 404, "COMMENT_NOT_FOUND");
        UUID otherPost = publishedPost(author, "football", "y");
        UUID onOther = comment(author, otherPost, "elsewhere", null);
        expectError(post(me, path, body("body", "hi", "parentId", onOther)), 404, "COMMENT_NOT_FOUND");
        expectError(post(me, "/api/v1/posts/" + UUID.randomUUID() + "/comments", body("body", "hi")), 404, "POST_NOT_FOUND");
        post(null, path, body("body", "hi")).andExpect(status().isUnauthorized());

        jdbc.update("update posts set allow_comments = false where id = ?", post);
        expectError(post(me, path, body("body", "hi")), 403, "COMMENTS_DISABLED");
        get(me, path).andExpect(status().isOk());
    }

    @Test
    void commentsFromPeopleWithABlockAreHiddenFromThePersonWhoBlocked() throws Exception {
        User author = user();
        User me = user();
        User blocked = user();
        UUID post = publishedPost(author, "football", "x");
        comment(blocked, post, "from the one I will block", null);
        comment(author, post, "from the author", null);
        get(me, "/api/v1/posts/" + post + "/comments").andExpect(jsonPath("$.items.length()").value(2));

        block(me, blocked);
        get(me, "/api/v1/posts/" + post + "/comments").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].body").value("from the author"));
    }

    @Test
    void theAuthorTheCommenterRepliedToAndMentionedPeopleAreEachToldOnce() throws Exception {
        User author = user();
        User me = user();
        User replied = user();
        User mentioned = user("ada_l");
        UUID post = publishedPost(author, "football", "x");
        UUID theirs = comment(replied, post, "first", null);

        post(me, "/api/v1/posts/" + post + "/comments",
                body("body", "agreed @ada_l, and @nobody_here too. @ada_l again", "parentId", theirs))
                .andExpect(status().isCreated());

        // The author heard about the first comment and about this reply.
        assertThat(count("select count(*) from notifications where user_id = ? and kind = 'comment'", author.id())).isEqualTo(2);
        assertThat(count("select count(*) from notifications where user_id = ? and kind = 'comment' and message like 'replied%'",
                replied.id())).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ? and kind = 'mention'", mentioned.id())).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ?", me.id())).isZero();
    }

    @Test
    void reactingToACommentNeedsAVisibleCommentOnAVisiblePost() throws Exception {
        User author = user();
        User me = user();
        User commenter = user();
        UUID post = publishedPost(author, "football", "x");
        UUID comment = comment(commenter, post, "c", null);

        expectError(put(me, "/api/v1/comments/" + UUID.randomUUID() + "/reaction", body()), 404, "COMMENT_NOT_FOUND");
        expectError(put(me, "/api/v1/comments/" + comment + "/reaction", body("kind", "bogus")), 400, "VALIDATION_FAILED");
        put(me, "/api/v1/comments/" + comment + "/reaction", body("kind", "fire")).andExpect(jsonPath("$.myReaction").value("fire"));
        block(commenter, me);
        expectError(put(me, "/api/v1/comments/" + comment + "/reaction", body("kind", "fire")), 404, "COMMENT_NOT_FOUND");
    }

    // --- saves and collections -----------------------------------------------------------

    @Test
    void savingAndUnsavingKeepsTheCountAndTheSavedList() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "keeper");
        String path = "/api/v1/posts/" + post + "/save";

        putEmpty(me, path).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.saves").value(1));
        putEmpty(me, path).andExpect(jsonPath("$.saves").value(1));
        get(me, "/api/v1/posts/" + post).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.saves").value(1));
        get(me, "/api/v1/me/saved").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(post.toString()));
        get(author, "/api/v1/me/saved").andExpect(jsonPath("$.items.length()").value(0));

        delete(me, path).andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.saves").value(0));
        get(me, "/api/v1/me/saved").andExpect(jsonPath("$.items.length()").value(0));
        expectError(putEmpty(me, "/api/v1/posts/" + UUID.randomUUID() + "/save"), 404, "POST_NOT_FOUND");
    }

    @Test
    void collectionsAreCreatedRenamedFilledAndDeleted() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "keeper");

        UUID memes = idOf(post(me, "/api/v1/me/collections", body("name", "Memes")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.posts").value(0)));
        expectError(post(me, "/api/v1/me/collections", body("name", "memes")), 409, "COLLECTION_EXISTS");
        expectError(post(me, "/api/v1/me/collections", body("name", " ")), 400, "VALIDATION_FAILED");
        post(author, "/api/v1/me/collections", body("name", "Memes")).andExpect(status().isCreated());

        putEmpty(me, "/api/v1/collections/" + memes + "/posts/" + post).andExpect(status().isNoContent());
        get(me, "/api/v1/me/collections").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].posts").value(1));
        get(me, "/api/v1/me/collections/" + memes).andExpect(jsonPath("$.items[0].id").value(post.toString()));
        get(me, "/api/v1/me/saved").andExpect(jsonPath("$.items.length()").value(1));

        patch(me, "/api/v1/collections/" + memes, body("name", "Best memes")).andExpect(jsonPath("$.name").value("Best memes"));
        delete(me, "/api/v1/collections/" + memes + "/posts/" + post).andExpect(status().isNoContent());
        get(me, "/api/v1/me/collections/" + memes).andExpect(jsonPath("$.items.length()").value(0));

        delete(me, "/api/v1/collections/" + memes).andExpect(status().isNoContent());
        get(me, "/api/v1/me/saved").andExpect(jsonPath("$.items.length()").value(1));
        expectError(get(me, "/api/v1/me/collections/" + memes), 404, "COLLECTION_NOT_FOUND");
    }

    @Test
    void savingIntoACollectionFilesItAndUnsavingTakesItBackOut() throws Exception {
        User author = user();
        User me = user();
        UUID post = publishedPost(author, "football", "keeper");
        UUID memes = idOf(post(me, "/api/v1/me/collections", body("name", "Memes")));

        putEmpty(me, "/api/v1/posts/" + post + "/save").andExpect(status().isOk());
        put(me, "/api/v1/posts/" + post + "/save", body("collectionId", memes)).andExpect(jsonPath("$.saves").value(1));
        get(me, "/api/v1/me/collections/" + memes).andExpect(jsonPath("$.items.length()").value(1));

        delete(me, "/api/v1/posts/" + post + "/save").andExpect(status().isOk());
        get(me, "/api/v1/me/collections/" + memes).andExpect(jsonPath("$.items.length()").value(0));
        expectError(put(me, "/api/v1/posts/" + post + "/save", body("collectionId", UUID.randomUUID())), 404, "COLLECTION_NOT_FOUND");
    }

    @Test
    void anotherPersonsCollectionIsTheSame404AsOneThatDoesNotExist() throws Exception {
        User author = user();
        User me = user();
        User other = user();
        UUID post = publishedPost(author, "football", "keeper");
        UUID mine = idOf(post(me, "/api/v1/me/collections", body("name", "Mine")));

        expectError(get(other, "/api/v1/me/collections/" + mine), 404, "COLLECTION_NOT_FOUND");
        expectError(patch(other, "/api/v1/collections/" + mine, body("name", "Stolen")), 404, "COLLECTION_NOT_FOUND");
        expectError(delete(other, "/api/v1/collections/" + mine), 404, "COLLECTION_NOT_FOUND");
        expectError(putEmpty(other, "/api/v1/collections/" + mine + "/posts/" + post), 404, "COLLECTION_NOT_FOUND");
        expectError(put(other, "/api/v1/posts/" + post + "/save", body("collectionId", mine)), 404, "COLLECTION_NOT_FOUND");
        assertThat(count("select count(*) from collection_posts")).isZero();
    }
}
