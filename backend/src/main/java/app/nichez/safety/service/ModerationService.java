package app.nichez.safety.service;

import app.nichez.common.ApiException;
import app.nichez.common.AuditService;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.ErrorCode;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.interaction.repository.CommentRepository;
import app.nichez.interaction.repository.CommentRepository.Basic;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.niche.repository.CommunityRepository;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.niche.service.NicheService;
import app.nichez.niche.service.NicheStatsService;
import app.nichez.post.entity.Post;
import app.nichez.post.entity.PostStatus;
import app.nichez.post.repository.PostRepository;
import app.nichez.safety.dto.SafetyDtos.ReportItem;
import app.nichez.safety.dto.SafetyDtos.VerdictResponse;
import app.nichez.safety.repository.ReportRepository;
import app.nichez.safety.repository.ReportRepository.Group;
import app.nichez.safety.repository.ReportRepository.Preview;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What moderators see and decide. A niche's queue is for its moderators and a community's for its
 * founder and moderators; admins (the {@code app_metadata} role, never {@code user_metadata}) see
 * everything. Authority is checked here, not in the controller. A report the caller may not act on
 * is the same 404 {@code REPORT_NOT_FOUND} as one that does not exist.
 */
@Service
public class ModerationService {

    public static final String REPORT_NOT_FOUND = "REPORT_NOT_FOUND";
    public static final String REPORT_ALREADY_RESOLVED = "REPORT_ALREADY_RESOLVED";
    public static final String VERDICT_NOT_APPLICABLE = "VERDICT_NOT_APPLICABLE";
    public static final String INVALID_TAB = "INVALID_TAB";
    public static final String REPORT_RESOLVED_EVENT = "REPORT_RESOLVED";

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final ReportRepository reports;
    private final NicheRepository nicheChecks;
    private final CommunityRepository communities;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final NicheStatsService nicheStats;
    private final MediaUrlProvider urls;
    private final AuditService audit;
    private final Clock clock;

    ModerationService(ReportRepository reports, NicheRepository nicheChecks, CommunityRepository communities,
            PostRepository posts, CommentRepository comments, NicheStatsService nicheStats, MediaUrlProvider urls,
            AuditService audit, Clock clock) {
        this.reports = reports;
        this.nicheChecks = nicheChecks;
        this.communities = communities;
        this.posts = posts;
        this.comments = comments;
        this.nicheStats = nicheStats;
        this.urls = urls;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code tab} is reported (open, oldest first), resolved (newest decision first) or requests (empty: join requests are on the members list). */
    @Transactional(readOnly = true)
    public CursorPage<ReportItem> nicheQueue(UUID nicheId, String tab, String cursor, Integer limit) {
        UUID me = CurrentUser.id();
        if (!nicheChecks.exists(nicheId)) {
            throw new NotFoundException(NicheService.NICHE_NOT_FOUND, "Niche not found.");
        }
        if (!CurrentUser.isAdmin() && !nicheChecks.isModerator(nicheId, me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Only moderators can see this queue.");
        }
        return queue("niche", nicheId, tab, cursor, limit);
    }

    @Transactional(readOnly = true)
    public CursorPage<ReportItem> communityQueue(UUID communityId, String tab, String cursor, Integer limit) {
        UUID me = CurrentUser.id();
        if (!communities.exists(communityId)) {
            throw new NotFoundException(ReportService.COMMUNITY_NOT_FOUND, "Community not found.");
        }
        if (!CurrentUser.isAdmin() && !communities.isModerator(communityId, me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Only moderators can see this queue.");
        }
        return queue("community", communityId, tab, cursor, limit);
    }

    private CursorPage<ReportItem> queue(String scope, UUID scopeId, String tab, String cursor, Integer requestedLimit) {
        String chosen = tab == null || tab.isBlank() ? "reported" : tab;
        if (!List.of("reported", "resolved", "requests").contains(chosen)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_TAB, "tab must be reported, resolved or requests.");
        }
        if ("requests".equals(chosen)) {
            return new CursorPage<>(List.of(), null);
        }
        int limit = Paging.limit(requestedLimit);
        Cursor.TimeId after = Cursor.TimeId.decode(cursor);
        Instant t = after == null ? null : after.time();
        UUID id = after == null ? null : after.id();
        List<Group> fetched = "reported".equals(chosen)
                ? reports.open(scope, scopeId, t, id, limit + 1)
                : reports.resolved(scope, scopeId, t, id, limit + 1);
        List<ReportItem> items = Paging.head(fetched, limit).stream().map(this::toItem).toList();
        return new CursorPage<>(items, Paging.nextCursor(fetched, limit, g -> new Cursor.TimeId(g.at(), g.id()).encode()));
    }

    private ReportItem toItem(Group group) {
        Optional<Preview> preview = switch (group.targetType()) {
            case "post" -> reports.postPreview(group.targetId());
            case "comment" -> reports.commentPreview(group.targetId());
            case "user" -> reports.userPreview(group.targetId());
            default -> reports.communityPreview(group.targetId());
        };
        return new ReportItem(group.id(), group.targetType(), group.targetId(), ReportReasons.title(group.reasonId()),
                group.count(), preview.map(Preview::authorHandle).orElse(null),
                preview.map(Preview::excerpt).orElse(""), preview.map(p -> urls.urlFor(p.mediaKey())).orElse(null),
                group.severe(), group.verdict());
    }

    /**
     * Decides every open report on the same target at once. {@code keep} leaves the content; {@code remove}
     * takes a post or comment down (a community only for admins); {@code ban} removes it and also keeps its
     * author out of the niche or community (400 {@code VERDICT_NOT_APPLICABLE} for anything that is not a post or comment).
     */
    @Transactional
    public VerdictResponse decide(UUID reportId, String verdict, String ipAddress) {
        UUID me = CurrentUser.id();
        ReportRepository.Basic report = reports.find(reportId).filter(r -> mayAct(me, r)).orElseThrow(ModerationService::notFound);
        if (!"open".equals(report.status())) {
            throw new ApiException(HttpStatus.CONFLICT, REPORT_ALREADY_RESOLVED, "This report has already been decided.");
        }
        Instant now = clock.instant();
        switch (verdict) {
            case "keep" -> {
                // Nothing to take down.
            }
            case "remove" -> takeDown(me, report, now, false);
            case "ban" -> takeDown(me, report, now, true);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Unknown verdict.");
        }
        int resolved = reports.resolveAll(report.targetType(), report.targetId(), verdict, me, now);
        reports.recordAction(report.id(), me, verdict, report.targetType(), report.targetId(), report.nicheId(),
                report.communityId(), now);
        audit.record(REPORT_RESOLVED_EVENT, ipAddress, Map.of(
                "reportId", report.id().toString(),
                "verdict", verdict,
                "targetType", report.targetType(),
                "targetId", report.targetId().toString(),
                "actorId", me.toString()));
        log.info("Report decided, report={}, verdict={}", report.id(), verdict);
        return new VerdictResponse(report.id(), verdict, resolved);
    }

    private void takeDown(UUID me, ReportRepository.Basic report, Instant now, boolean ban) {
        switch (report.targetType()) {
            case "post" -> {
                Post post = posts.findByIdForUpdate(report.targetId()).orElseThrow(ModerationService::notFound);
                if (post.getStatus() != PostStatus.DELETED && post.getStatus() != PostStatus.REMOVED) {
                    PostStatus before = post.getStatus();
                    post.markRemoved(now);
                    posts.saveAndFlush(post);
                    if (before == PostStatus.PUBLISHED) {
                        nicheStats.evict(post.getNicheId());
                    }
                }
                if (ban) {
                    banAuthor(me, report, post.getAuthorId(), now);
                }
            }
            case "comment" -> {
                Basic comment = comments.find(report.targetId()).orElseThrow(ModerationService::notFound);
                if (!comment.deleted()) {
                    comments.softDelete(comment.id(), now);
                }
                if (ban) {
                    banAuthor(me, report, comment.authorId(), now);
                }
            }
            case "community" -> {
                if (ban || !CurrentUser.isAdmin()) {
                    throw notApplicable();
                }
                reports.takeDownCommunity(report.targetId(), now);
            }
            default -> throw notApplicable();
        }
    }

    private void banAuthor(UUID me, ReportRepository.Basic report, UUID author, Instant now) {
        boolean community = report.communityId() != null;
        UUID scopeId = community ? report.communityId() : report.nicheId();
        boolean authorModerates = community ? communities.isModerator(scopeId, author) : nicheChecks.isModerator(scopeId, author);
        if (scopeId == null || authorModerates || author.equals(me)) {
            throw notApplicable();
        }
        reports.ban(community ? "community" : "niche", scopeId, author, me, now);
        if (community) {
            reports.removeCommunityMember(scopeId, author);
        } else {
            reports.removeNicheMember(scopeId, author);
        }
    }

    private boolean mayAct(UUID me, ReportRepository.Basic report) {
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (report.communityId() != null) {
            return communities.isModerator(report.communityId(), me);
        }
        return report.nicheId() != null && nicheChecks.isModerator(report.nicheId(), me);
    }

    private static NotFoundException notFound() {
        return new NotFoundException(REPORT_NOT_FOUND, "Report not found.");
    }

    private static ApiException notApplicable() {
        return new ApiException(HttpStatus.BAD_REQUEST, VERDICT_NOT_APPLICABLE, "That decision does not apply here.");
    }
}
