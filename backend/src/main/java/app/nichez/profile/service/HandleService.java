package app.nichez.profile.service;

import app.nichez.common.ApiException;
import app.nichez.common.ConflictException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.ProfileProperties;
import app.nichez.profile.dto.ClaimHandleResponse;
import app.nichez.profile.dto.HandleCheckResponse;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.service.HandleRules.Violation;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Handle availability and claiming. Who is asking always comes from the verified token
 * ({@link CurrentUser}), never from the request.
 */
@Service
public class HandleService {

    public static final String HANDLE_INVALID = "HANDLE_INVALID";
    public static final String HANDLE_RESERVED = "HANDLE_RESERVED";
    public static final String HANDLE_TAKEN = "HANDLE_TAKEN";

    /** A user is onboarded once they have a handle and at least this many niches (API.md: min 3). */
    public static final int MIN_NICHES_TO_ONBOARD = 3;

    private static final Logger log = LoggerFactory.getLogger(HandleService.class);

    private final ProfileRepository profiles;
    private final HandleSuggester suggester;
    private final SlidingWindowRateLimiter limiter;
    private final ProfileProperties properties;
    private final Clock clock;

    HandleService(ProfileRepository profiles, HandleSuggester suggester, SlidingWindowRateLimiter limiter,
            ProfileProperties properties, Clock clock) {
        this.profiles = profiles;
        this.suggester = suggester;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Live check while the user types. A handle that breaks a rule is simply "not available";
     * it is not an error, so the screen can keep showing suggestions.
     */
    public HandleCheckResponse check(String handle) {
        UUID me = CurrentUser.id();
        limiter.check("handle-check:" + me, properties.handleCheckPerMinute(), Duration.ofMinutes(1));

        String input = handle == null ? "" : handle;
        List<String> reserved = profiles.reservedHandles();
        List<String> verified = profiles.verifiedHandles(me);
        boolean available = HandleRules.check(input, reserved, verified).isEmpty()
                && !profiles.isHandleTaken(input, me);
        if (available) {
            return new HandleCheckResponse(true, List.of());
        }
        return new HandleCheckResponse(false, suggester.suggest(input, me, reserved, verified));
    }

    /**
     * Gives the caller this handle. Rule failures are 400 (HANDLE_INVALID, HANDLE_RESERVED).
     * Availability is decided by the unique index, not by a read-then-write, so two people
     * claiming the same handle at once cannot both win: the loser gets 409 HANDLE_TAKEN.
     *
     * <p>Deliberately not one transaction: after a unique violation Postgres aborts the
     * transaction, and we still need to ask whether the "violation" was the caller racing
     * themselves (a double tap), which is a success, not a conflict.
     */
    public ClaimHandleResponse claim(String handle) {
        UUID me = CurrentUser.id();
        Optional<Violation> violation = HandleRules.check(handle, profiles.reservedHandles(),
                profiles.verifiedHandles(me));
        if (violation.isPresent()) {
            throw switch (violation.get()) {
                case INVALID -> new ApiException(HttpStatus.BAD_REQUEST, HANDLE_INVALID,
                        "Handles are 3 to 30 characters: letters, numbers, dots and underscores.");
                case RESERVED -> new ApiException(HttpStatus.BAD_REQUEST, HANDLE_RESERVED,
                        "That handle is not available.");
            };
        }
        try {
            profiles.upsertHandle(me, handle, clock.instant());
        } catch (DuplicateKeyException e) {
            if (!profiles.isHandleOwnedBy(handle, me)) {
                throw new ConflictException(HANDLE_TAKEN, "That handle is taken.");
            }
        }
        if (profiles.markOnboardedIfComplete(me, MIN_NICHES_TO_ONBOARD, clock.instant())) {
            log.info("User onboarded, user={}", me);
        }
        return new ClaimHandleResponse(handle);
    }
}
