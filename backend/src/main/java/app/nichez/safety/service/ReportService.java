package app.nichez.safety.service;

import app.nichez.common.ApiException;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.SocialProperties;
import app.nichez.graph.service.FollowService;
import app.nichez.interaction.repository.CommentRepository;
import app.nichez.interaction.repository.CommentRepository.Basic;
import app.nichez.interaction.service.CommentService;
import app.nichez.niche.repository.NicheCatalogRepository;
import app.nichez.niche.repository.NicheCatalogRepository.Niche;
import app.nichez.niche.service.NicheService;
import app.nichez.post.entity.Post;
import app.nichez.post.service.PostAccess;
import app.nichez.profile.repository.UserRepository;
import app.nichez.safety.dto.SafetyDtos.ReportReason;
import app.nichez.safety.dto.SafetyDtos.ReportReceived;
import app.nichez.safety.repository.ReportRepository;
import app.nichez.safety.repository.ReportRepository.PostScope;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting a post, a comment, a person or a community. Who is asking always comes from the
 * token. A person reports a target once; reporting it again returns the same report. Whatever the
 * caller cannot see is the same 404 as something that does not exist.
 */
@Service
public class ReportService {

    public static final String CANNOT_REPORT_OWN_CONTENT = "CANNOT_REPORT_OWN_CONTENT";
    public static final String COMMUNITY_NOT_FOUND = "COMMUNITY_NOT_FOUND";

    private final ReportRepository reports;
    private final PostAccess access;
    private final CommentRepository comments;
    private final UserRepository users;
    private final BlockPolicy blocks;
    private final NicheCatalogRepository niches;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    ReportService(ReportRepository reports, PostAccess access, CommentRepository comments, UserRepository users,
            BlockPolicy blocks, NicheCatalogRepository niches, SlidingWindowRateLimiter limiter,
            SocialProperties social, Clock clock) {
        this.reports = reports;
        this.access = access;
        this.comments = comments;
        this.users = users;
        this.blocks = blocks;
        this.niches = niches;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    /** Reasons for a niche; "religious disrespect" appears in strict (faith) groups. */
    @Transactional(readOnly = true)
    public List<ReportReason> reasonsFor(UUID nicheId) {
        Niche niche = niches.find(nicheId)
                .orElseThrow(() -> new NotFoundException(NicheService.NICHE_NOT_FOUND, "Niche not found."));
        return ReportReasons.forNiche(niche.label(), niche.strict());
    }

    public List<ReportReason> generalReasons() {
        return ReportReasons.forNiche(null, false);
    }

    @Transactional
    public ReportReceived report(String targetType, UUID targetId, String reasonId, String note) {
        UUID me = CurrentUser.id();
        limiter.check("report:" + me, social.reportsPerHour(), Duration.ofHours(1));
        UUID author;
        UUID nicheId = null;
        UUID communityId = null;
        switch (targetType) {
            case "post" -> {
                Post post = access.requireInteractable(me, targetId);
                author = post.getAuthorId();
                PostScope scope = reports.postScope(targetId).orElseThrow(PostAccess::notFound);
                nicheId = scope.nicheId();
                communityId = scope.communityId();
            }
            case "comment" -> {
                Basic comment = comments.find(targetId).filter(c -> !c.deleted())
                        .orElseThrow(() -> new NotFoundException(CommentService.COMMENT_NOT_FOUND, "Comment not found."));
                access.requireInteractable(me, comment.postId());
                if (blocks.isBlockedEitherWay(me, comment.authorId())) {
                    throw new NotFoundException(CommentService.COMMENT_NOT_FOUND, "Comment not found.");
                }
                author = comment.authorId();
                PostScope scope = reports.postScope(comment.postId()).orElseThrow(PostAccess::notFound);
                nicheId = scope.nicheId();
                communityId = scope.communityId();
            }
            case "user" -> {
                if (!users.isActive(targetId) || (!me.equals(targetId) && blocks.isBlockedEitherWay(me, targetId))) {
                    throw new NotFoundException(FollowService.USER_NOT_FOUND, "User not found.");
                }
                author = targetId;
            }
            case "community" -> {
                if (!reports.communityVisible(me, targetId)) {
                    throw new NotFoundException(COMMUNITY_NOT_FOUND, "Community not found.");
                }
                author = reports.communityOwner(targetId).orElse(null);
                communityId = targetId;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Unknown target type.");
        }
        if (me.equals(author)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CANNOT_REPORT_OWN_CONTENT, "You cannot report your own content.");
        }
        UUID id = reports.insert(me, targetType, targetId, nicheId, communityId, reasonId,
                note == null || note.isBlank() ? null : note.strip(), ReportReasons.severe(reasonId), clock.instant());
        return new ReportReceived(id);
    }
}
