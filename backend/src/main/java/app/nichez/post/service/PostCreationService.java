package app.nichez.post.service;

import app.nichez.common.ApiException;
import app.nichez.common.AuditService;
import app.nichez.common.ConflictException;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.PostProperties;
import app.nichez.config.UploadProperties;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaStatus;
import app.nichez.media.entity.UploadType;
import app.nichez.media.repository.MediaProcessingJobRepository;
import app.nichez.media.repository.MediaRepository;
import app.nichez.media.service.MediaObjectStore;
import app.nichez.media.service.MediaObjectStore.StoredObject;
import app.nichez.media.service.UploadService;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.niche.service.NicheStatsService;
import app.nichez.post.dto.CreatePostRequest;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.entity.Audience;
import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostTag;
import app.nichez.post.repository.PostRepository;
import app.nichez.post.repository.PostTagRepository;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.safety.service.BlockPolicy;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /posts}: turns an uploaded file into a post that is processing.
 *
 * <p>Every check that can be made without writing runs first, including the look at the stored
 * file, so no database transaction is held open across calls to storage. The write then locks the
 * media row and checks again that it is still unused, so two requests for the same media cannot
 * both succeed (the unique {@code posts.media_id} is the last line of defence).
 */
@Service
public class PostCreationService {

    public static final String MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND";
    public static final String MEDIA_ALREADY_USED = "MEDIA_ALREADY_USED";
    public static final String MEDIA_NOT_UPLOADED = "MEDIA_NOT_UPLOADED";
    public static final String MEDIA_TYPE_MISMATCH = "MEDIA_TYPE_MISMATCH";
    public static final String NOT_A_NICHE_MEMBER = "NOT_A_NICHE_MEMBER";
    public static final String TAGGED_USER_NOT_FOUND = "TAGGED_USER_NOT_FOUND";
    public static final String POST_CREATED_EVENT = "POST_CREATED";

    private static final Logger log = LoggerFactory.getLogger(PostCreationService.class);

    private final PostRepository posts;
    private final PostTagRepository tags;
    private final MediaRepository media;
    private final MediaProcessingJobRepository jobs;
    private final MediaObjectStore store;
    private final NicheRepository niches;
    private final ProfileRepository profiles;
    private final BlockPolicy blocks;
    private final PostViews views;
    private final SlidingWindowRateLimiter limiter;
    private final PostProperties postProperties;
    private final UploadProperties uploadProperties;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PostCreationService(PostRepository posts, PostTagRepository tags, MediaRepository media,
            MediaProcessingJobRepository jobs, MediaObjectStore store, NicheRepository niches,
            ProfileRepository profiles, BlockPolicy blocks, PostViews views, SlidingWindowRateLimiter limiter,
            PostProperties postProperties, UploadProperties uploadProperties, AuditService audit,
            TransactionTemplate transaction, Clock clock) {
        this.posts = posts;
        this.tags = tags;
        this.media = media;
        this.jobs = jobs;
        this.store = store;
        this.niches = niches;
        this.profiles = profiles;
        this.blocks = blocks;
        this.views = views;
        this.limiter = limiter;
        this.postProperties = postProperties;
        this.uploadProperties = uploadProperties;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    public PostResponse create(CreatePostRequest request, String ipAddress) {
        UUID me = CurrentUser.id();
        limiter.check("post-create:" + me, postProperties.createPerHour(), Duration.ofHours(1));

        Media upload = media.findByIdAndOwnerId(request.mediaId(), me).orElseThrow(PostCreationService::mediaNotFound);
        if (!isUnused(upload)) {
            throw mediaAlreadyUsed();
        }
        if (niches.labelOf(request.nicheId()).isEmpty()) {
            throw new NotFoundException(NicheStatsService.NICHE_NOT_FOUND, "Niche not found.");
        }
        if (!niches.isMember(request.nicheId(), me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_NICHE_MEMBER,
                    "Join this niche before posting to it.");
        }
        Set<UUID> tagged = checkTags(me, request.taggedUserIds());
        StoredObject stored = checkStoredFile(upload);

        return transaction.execute(status -> persist(me, request, tagged, stored, ipAddress));
    }

    private PostResponse persist(UUID me, CreatePostRequest request, Set<UUID> tagged, StoredObject stored,
            String ipAddress) {
        Media locked = media.findByIdAndOwnerIdForUpdate(request.mediaId(), me)
                .orElseThrow(PostCreationService::mediaNotFound);
        if (!isUnused(locked)) {
            throw mediaAlreadyUsed();
        }

        Instant now = clock.instant();
        Post post = new Post(UUID.randomUUID(), me, request.nicheId(), locked.getId(), captionOf(request),
                request.audience() == null ? Audience.EVERYONE : Audience.valueOf(request.audience().toUpperCase(java.util.Locale.ROOT)),
                request.allowComments() == null || request.allowComments(), now);
        posts.saveAndFlush(post);
        tags.saveAll(tagged.stream().map(user -> new PostTag(post.getId(), user)).toList());

        locked.setStatus(MediaStatus.PROCESSING);
        locked.setBytes(stored.bytes());
        media.saveAndFlush(locked);
        jobs.enqueue(locked.getId(), post.getId(), now);

        audit.record(POST_CREATED_EVENT, ipAddress, Map.of(
                "postId", post.getId().toString(),
                "authorId", me.toString(),
                "nicheId", post.getNicheId().toString(),
                "mediaId", locked.getId().toString(),
                "kind", locked.getKind().dbValue(),
                "taggedCount", tagged.size()));
        log.info("Post created, post={}, media={}", post.getId(), locked.getId());
        return views.of(post, locked);
    }

    /** A media can back one post, and only before it has been handed to processing. */
    private boolean isUnused(Media candidate) {
        return (candidate.getStatus() == MediaStatus.PENDING || candidate.getStatus() == MediaStatus.UPLOADED)
                && posts.findByMediaId(candidate.getId()).isEmpty();
    }

    /**
     * Every tagged user must exist and must not be blocked either way with the author. Both cases
     * get the same answer, so a refusal never reveals a block.
     */
    private Set<UUID> checkTags(UUID me, List<UUID> requested) {
        Set<UUID> tagged = requested == null ? Set.of() : new LinkedHashSet<>(requested);
        if (tagged.isEmpty()) {
            return tagged;
        }
        if (!profiles.existingIds(tagged).containsAll(tagged) || !blocks.blockedEitherWay(me, tagged).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TAGGED_USER_NOT_FOUND,
                    "One or more tagged people could not be found.");
        }
        return tagged;
    }

    /** The stored file must exist, fit its kind's size cap, and really be the type it was declared as. */
    private StoredObject checkStoredFile(Media upload) {
        StoredObject stored = store.inspect(upload.getOriginalKey(), UploadType.SNIFF_BYTES)
                .orElseThrow(() -> new ConflictException(MEDIA_NOT_UPLOADED,
                        "The file has not been uploaded yet."));
        if (stored.bytes() > uploadProperties.maxBytes(upload.getKind())) {
            throw UploadService.tooLarge();
        }
        Optional<UploadType> declared = UploadType.fromMimeType(upload.getDeclaredType());
        Optional<UploadType> actual = UploadType.sniff(stored.head());
        if (actual.isEmpty() || !actual.equals(declared)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MEDIA_TYPE_MISMATCH,
                    "The uploaded file is not the type it was declared as.");
        }
        return stored;
    }

    private static String captionOf(CreatePostRequest request) {
        String caption = request.caption();
        return caption == null || caption.isBlank() ? null : caption;
    }

    private static NotFoundException mediaNotFound() {
        return new NotFoundException(MEDIA_NOT_FOUND, "Media not found.");
    }

    private static ConflictException mediaAlreadyUsed() {
        return new ConflictException(MEDIA_ALREADY_USED, "This media is already used by a post.");
    }
}
