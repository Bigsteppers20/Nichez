package app.nichez.profile.service;

import app.nichez.media.service.MediaUrlProvider;
import app.nichez.profile.dto.ProfileDtos.UserProfileResponse;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.repository.UserRepository.ProfileRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds a {@link UserProfileResponse} from a profile row, signing the photo URLs. */
@Component
class ProfileViews {

    private final UserRepository users;
    private final MediaUrlProvider urls;

    ProfileViews(UserRepository users, MediaUrlProvider urls) {
        this.users = users;
        this.urls = urls;
    }

    UserProfileResponse of(ProfileRow row, boolean isFollowing, boolean isYou) {
        List<String> niches = users.nicheLabels(row.id());
        String avatar = row.avatarKey() != null ? urls.urlFor(row.avatarKey()) : row.avatarUrl();
        return new UserProfileResponse(row.id(), row.name(), row.handle(), row.bio() == null ? "" : row.bio(), avatar,
                urls.urlFor(row.coverKey()), row.verified(), row.followers(), row.following(), row.postCount(),
                row.totalViews(), niches, isFollowing, isYou);
    }

    /** A signed-in person who has not written anything to their profile yet. */
    static ProfileRow blank(UUID id) {
        return new ProfileRow(id, null, null, null, false, null, null, null, 0, 0, 0, 0, false, null, null, null);
    }
}
