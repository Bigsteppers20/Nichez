package app.nichez.profile.service;

import app.nichez.media.service.MediaUrlProvider;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.repository.UserRepository.BriefRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads the short form of a set of people in one query, with their photo URLs signed. */
@Component
public class UserBriefs {

    private final UserRepository users;
    private final MediaUrlProvider urls;

    UserBriefs(UserRepository users, MediaUrlProvider urls) {
        this.users = users;
        this.urls = urls;
    }

    /** People who do not exist are simply absent from the result. */
    public Map<UUID, UserBrief> load(Collection<UUID> ids) {
        Map<UUID, UserBrief> result = new HashMap<>();
        for (BriefRow row : users.briefs(ids.stream().distinct().toList())) {
            result.put(row.id(), new UserBrief(row.id(), row.name(), row.handle(), avatarOf(row), row.verified()));
        }
        return result;
    }

    public UserBrief loadOrPlaceholder(UUID id, Map<UUID, UserBrief> loaded) {
        UserBrief brief = loaded.get(id);
        return brief != null ? brief : new UserBrief(id, null, null, null, false);
    }

    private String avatarOf(BriefRow row) {
        return row.avatarKey() != null ? urls.urlFor(row.avatarKey()) : row.avatarUrl();
    }
}
