package app.nichez.stream.service;

import app.nichez.common.ApiException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.SocialProperties;
import app.nichez.interaction.dto.InteractionDtos.ViewReport;
import app.nichez.interaction.dto.InteractionDtos.ViewsRequest;
import app.nichez.interaction.dto.InteractionDtos.ViewsResponse;
import app.nichez.post.entity.Post;
import app.nichez.post.repository.PostRepository;
import app.nichez.post.repository.PostVisibilityRepository;
import app.nichez.security.CurrentUser;
import app.nichez.stream.repository.ViewEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Playback reports from the app. The phone says what it saw; the server decides what counts, so a
 * report is never trusted to be a view. A playback counts when it lasted long enough
 * ({@code social.view-min-watched-ms}), is not the author watching their own post, and is the
 * viewer's first qualifying view of that post today.
 *
 * <p>A report for a post the caller cannot see is dropped without a word, so the response never
 * says whether a post exists.
 */
@Service
public class ViewService {

    public static final String TOO_MANY_VIEWS = "TOO_MANY_VIEWS";

    private final ViewEventRepository events;
    private final PostRepository posts;
    private final PostVisibilityRepository visibility;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final Clock clock;

    ViewService(ViewEventRepository events, PostRepository posts, PostVisibilityRepository visibility,
            SlidingWindowRateLimiter limiter, SocialProperties social, Clock clock) {
        this.events = events;
        this.posts = posts;
        this.visibility = visibility;
        this.limiter = limiter;
        this.social = social;
        this.clock = clock;
    }

    @Transactional
    public ViewsResponse report(ViewsRequest request) {
        UUID me = CurrentUser.id();
        if (request.views().size() > social.viewBatchMax()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TOO_MANY_VIEWS,
                    "Send at most " + social.viewBatchMax() + " views at a time.");
        }
        limiter.check("views:" + me, social.viewBatchesPerMinute(), Duration.ofMinutes(1));
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int received = 0;
        int qualified = 0;
        for (ViewReport report : request.views()) {
            Post post = posts.findById(report.postId()).filter(p -> visibility.canInteract(me, p.getId())).orElse(null);
            if (post == null) {
                continue;
            }
            received++;
            if (post.getAuthorId().equals(me)) {
                events.insertRejected(post.getId(), me, report.watchedMs(), day, "self_view");
            } else if (report.watchedMs() < social.viewMinWatchedMs()) {
                events.insertRejected(post.getId(), me, report.watchedMs(), day, "too_short");
            } else if (events.insertQualifying(post.getId(), me, report.watchedMs(), day)) {
                qualified++;
            } else {
                events.insertRejected(post.getId(), me, report.watchedMs(), day, "duplicate_today");
            }
        }
        return new ViewsResponse(received, qualified);
    }
}
