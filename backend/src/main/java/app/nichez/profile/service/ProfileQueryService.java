package app.nichez.profile.service;

import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.ErrorCode;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.graph.repository.FollowRepository;
import app.nichez.graph.service.FollowService;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.post.service.PostPages;
import app.nichez.profile.dto.ProfileDtos.UserProfileResponse;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.repository.UserRepository.ProfileRow;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading other people's profiles and their posts. A person who does not exist, has no handle yet,
 * is deactivated or erased, or has a block with the caller either way is the same 404
 * {@code USER_NOT_FOUND}, so a block is never revealed.
 */
@Service
public class ProfileQueryService {

    public static final String INVALID_TAB = "INVALID_TAB";

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final UserRepository users;
    private final FollowRepository follows;
    private final PostListRepository lists;
    private final PostPages pages;
    private final ProfileViews views;
    private final BlockPolicy blocks;

    ProfileQueryService(UserRepository users, FollowRepository follows, PostListRepository lists, PostPages pages,
            ProfileViews views, BlockPolicy blocks) {
        this.users = users;
        this.follows = follows;
        this.lists = lists;
        this.pages = pages;
        this.views = views;
        this.blocks = blocks;
    }

    /** {@code handleOrId} is a handle (with or without a leading @) or a user id. */
    @Transactional(readOnly = true)
    public UserProfileResponse get(String handleOrId) {
        UUID me = CurrentUser.id();
        ProfileRow row = lookup(handleOrId).filter(r -> visible(me, r)).orElseThrow(ProfileQueryService::notFound);
        boolean isYou = row.id().equals(me);
        boolean following = !isYou && follows.followedAmong(me, Set.of(row.id())).contains(row.id());
        return views.of(row, following, isYou);
    }

    /**
     * {@code tab} is posts (default), media (video posts only) or saved (the caller's own only;
     * anyone else's is 403 {@code ACCESS_DENIED}).
     */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> posts(UUID userId, String tab, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        String chosen = tab == null || tab.isBlank() ? "posts" : tab;
        if (!List.of("posts", "media", "saved").contains(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_TAB, "tab must be posts, media or saved.");
        }
        if ("saved".equals(chosen) && !me.equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Only you can see what you saved.");
        }
        ProfileRow row = users.profile(userId).filter(r -> visible(me, r)).orElseThrow(ProfileQueryService::notFound);
        int limit = Paging.limit(requestedLimit);
        Cursor.TimeId after = Cursor.TimeId.decode(cursor);
        List<Item> fetched = "saved".equals(chosen)
                ? lists.saved(me, after, limit + 1)
                : lists.byAuthor(me, row.id(), "media".equals(chosen), after, limit + 1);
        return pages.page(me, fetched, limit);
    }

    private Optional<ProfileRow> lookup(String handleOrId) {
        String key = handleOrId.startsWith("@") ? handleOrId.substring(1) : handleOrId;
        return UUID_TEXT.matcher(key).matches() ? users.profile(UUID.fromString(key)) : users.profileByHandle(key);
    }

    private boolean visible(UUID me, ProfileRow row) {
        if (row.id().equals(me)) {
            return true;
        }
        return row.handle() != null && row.deactivatedAt() == null && row.deletionRequestedAt() == null
                && !blocks.isBlockedEitherWay(me, row.id());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(FollowService.USER_NOT_FOUND, "User not found.");
    }
}
