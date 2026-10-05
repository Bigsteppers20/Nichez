package app.nichez.post.service;

import app.nichez.common.TimeAgo;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaKind;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.entity.Post;
import app.nichez.profile.repository.ProfileRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Builds the {@link PostResponse} for a post the caller has already been allowed to see. */
@Component
class PostViews {

    private final ProfileRepository profiles;
    private final NicheRepository niches;
    private final MediaUrlProvider urls;
    private final Clock clock;

    PostViews(ProfileRepository profiles, NicheRepository niches, MediaUrlProvider urls, Clock clock) {
        this.profiles = profiles;
        this.niches = niches;
        this.urls = urls;
        this.clock = clock;
    }

    PostResponse of(Post post, Media media) {
        PostResponse.Author author = profiles.summaryOf(post.getAuthorId())
                .map(p -> new PostResponse.Author(p.id(), p.name(), p.handle(), p.avatarUrl()))
                .orElse(new PostResponse.Author(post.getAuthorId(), null, null, null));
        // Until processing finishes there is no final file; the author's preview is their own upload.
        String mediaKey = media.getFinalKey() != null ? media.getFinalKey() : media.getOriginalKey();
        return new PostResponse(
                post.getId(),
                author,
                post.getNicheId(),
                niches.labelOf(post.getNicheId()).orElse(null),
                post.getCaption(),
                post.getAudience().dbValue(),
                post.isAllowComments(),
                post.getStatus().dbValue(),
                media.getKind() == MediaKind.VIDEO,
                urls.urlFor(mediaKey),
                urls.urlFor(media.getPosterKey()),
                post.isSensitive(),
                post.getSensitiveReason(),
                post.getReactionCount(),
                post.getCommentCount(),
                post.getCreatedAt(),
                TimeAgo.between(post.getCreatedAt(), clock.instant()));
    }
}
