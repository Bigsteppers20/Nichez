package app.nichez.post.service;

import app.nichez.common.ApiException;
import app.nichez.common.AuditService;
import app.nichez.common.ConflictException;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.PostProperties;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaStatus;
import app.nichez.media.repository.MediaProcessingJobRepository;
import app.nichez.media.repository.MediaRepository;
import app.nichez.media.service.MediaObjectStore.StoredObject;
import app.nichez.media.service.MediaVerifier;
import app.nichez.niche.repository.CommunityRepository;
import app.nichez.niche.repository.NicheCatalogRepository;
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
    public static final String MEDIA_NOT_UPLOADED = MediaVerifier.MEDIA_NOT_UPLOADED;
    public static final String MEDIA_TYPE_MISMATCH = MediaVerifier.MEDIA_TYPE_MISMATCH;
    public static final String NOT_A_NICHE_MEMBER = "NOT_A_NICHE_MEMBER";
    public static final String TAGGED_USER_NOT_FOUND = "TAGGED_USER_NOT_FOUND";
    public static final String POST_CREATED_EVENT = "POST_CREATED";
    public static final String NOT_A_COMMUNITY_MEMBER = "NOT_A_COMMUNITY_MEMBER";
    public static final String NICHE_COMMUNITY_MISMATCH = "NICHE_COMMUNITY_MISMATCH";
    public static final String BANNED_FROM_NICHE = "BANNED_FROM_NICHE";
    public static final String BANNED_FROM_COMMUNITY = "BANNED_FROM_COMMUNITY";

    private static final Logger log = LoggerFactory.getLogger(PostCreationService.class);

    private final PostRepository posts;
    private final PostTagRepository tags;
    private final MediaRepository media;
    private final MediaProcessingJobRepository jobs;
    private final MediaVerifier verifier;
    private final NicheRepository niches;
    private final NicheCatalogRepository catalog;
    private final CommunityRepository communities;
    private final ProfileRepository profiles;
    private final BlockPolicy blocks;
    private final PostCards cards;
    private final SlidingWindowRateLimiter limiter;
    private final PostProperties postProperties;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PostCreationService(PostRepository posts, PostTagRepository tags, MediaRepository media,
            MediaProcessingJobRepository jobs, MediaVerifier verifier, NicheRepository niches,
            NicheCatalogRepository catalog, CommunityRepository communities,
            ProfileRepository profiles, BlockPolicy blocks, PostCards cards, SlidingWindowRateLimiter limiter,
            PostProperties postProperties, AuditService audit,
            TransactionTemplate transaction, Clock clock) {
        this.posts = posts;
        this.tags = tags;
        this.media = media;
        this.jobs = jobs;
        this.verifier = verifier;
        this.niches = niches;
        this.catalog = catalog;
        this.communities = communities;
        this.profiles = profiles;
        this.blocks = blocks;
        this.cards = cards;
        this.limiter = limiter;
        this.postProperties = postProperties;
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
        if (catalog.isBanned(me, request.nicheId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, BANNED_FROM_NICHE, "You cannot post to this niche.");
        }
        if (request.communityId() != null) {
            checkCommunity(me, request);
        } else if (!niches.isMember(request.nicheId(), me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_NICHE_MEMBER,
                    "Join this niche before posting to it.");
        }
        Set<UUID> tagged = checkTags(me, request.taggedUserIds());
        StoredObject stored = verifier.verifyStored(upload);

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
        post.setCommunityId(request.communityId());
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
                "taggedCount", tagged.size(),
                "communityId", String.valueOf(request.communityId())));
        log.info("Post created, post={}, media={}", post.getId(), locked.getId());
        return cards.loadOne(me, post.getId()).orElseThrow();
    }

    /** Posting into a community: the caller is a member, not banned, and the niche is the community's home niche. */
    private void checkCommunity(UUID me, CreatePostRequest request) {
        var community = communities.find(me, request.communityId())
                .orElseThrow(() -> new NotFoundException("COMMUNITY_NOT_FOUND", "Community not found."));
        if (communities.isBanned(request.communityId(), me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, BANNED_FROM_COMMUNITY, "You cannot post to this community.");
        }
        if (community.myRole() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, NOT_A_COMMUNITY_MEMBER, "Join this community before posting to it.");
        }
        if (!community.homeNicheId().equals(request.nicheId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NICHE_COMMUNITY_MISMATCH,
                    "A community's posts go to its home niche.");
        }
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
