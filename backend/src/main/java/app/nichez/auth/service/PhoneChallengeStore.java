package app.nichez.auth.service;

import app.nichez.auth.entity.OtpSend;
import app.nichez.auth.entity.PhoneChallenge;
import app.nichez.auth.repository.OtpSendRepository;
import app.nichez.auth.repository.PhoneChallengeRepository;
import app.nichez.common.ApiException;
import app.nichez.common.AuditService;
import app.nichez.common.Masking;
import app.nichez.common.NotFoundException;
import app.nichez.common.RateLimitedException;
import app.nichez.config.SmsAuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database step of the phone sign-in flow, one short transaction each.
 *
 * <p>Kept apart from {@link PhoneAuthService} on purpose: the calls to Supabase
 * must happen between these transactions, never inside one, so a row lock is not
 * held while waiting on the network. The attempt counter is reserved BEFORE the
 * code is checked, under a row lock, so parallel guesses cannot beat the limit.
 */
@Service
public class PhoneChallengeStore {

    static final String CHALLENGE_NOT_FOUND = "CHALLENGE_NOT_FOUND";
    static final String CODE_EXPIRED = "CODE_EXPIRED";
    static final String LOCKED = "LOCKED";
    static final String OTP_LOCKED_EVENT = "OTP_LOCKED";

    private static final Logger log = LoggerFactory.getLogger(PhoneChallengeStore.class);
    private static final Duration WINDOW = Duration.ofHours(1);

    private final PhoneChallengeRepository challenges;
    private final OtpSendRepository sends;
    private final AuditService audit;
    private final SmsAuthProperties sms;
    private final Clock clock;

    PhoneChallengeStore(PhoneChallengeRepository challenges, OtpSendRepository sends, AuditService audit,
            SmsAuthProperties sms, Clock clock) {
        this.challenges = challenges;
        this.sends = sends;
        this.audit = audit;
        this.sms = sms;
        this.clock = clock;
    }

    /** New challenge for a phone, after the lockout and hourly limits pass. Records the send. */
    @Transactional
    public PhoneChallenge begin(String phoneE164, String ip) {
        Instant now = clock.instant();
        requireNotLocked(phoneE164, now);
        requireUnderSendLimits(phoneE164, ip, now);
        PhoneChallenge challenge = challenges.save(
                new PhoneChallenge(UUID.randomUUID(), phoneE164, now, now.plus(sms.codeTtl())));
        sends.save(new OtpSend(challenge.getId(), phoneE164, ip, now));
        return challenge;
    }

    /** Another code for an existing challenge: lockout, 60 s cooldown and hourly limits apply. Records the send. */
    @Transactional
    public PhoneChallenge beginResend(UUID challengeId, String ip) {
        Instant now = clock.instant();
        PhoneChallenge challenge = lockedOpenChallenge(challengeId);
        requireNotLocked(challenge.getPhoneE164(), now);
        Instant nextAllowed = challenge.getLastSentAt().plus(sms.resendCooldown());
        if (nextAllowed.isAfter(now)) {
            throw new RateLimitedException(Duration.between(now, nextAllowed));
        }
        requireUnderSendLimits(challenge.getPhoneE164(), ip, now);
        challenge.codeSent(now, now.plus(sms.codeTtl()));
        sends.save(new OtpSend(challenge.getId(), challenge.getPhoneE164(), ip, now));
        return challenges.save(challenge);
    }

    /**
     * Counts one guess against the challenge before the code is checked. Throws 404, 423 or 410
     * when no guess is allowed. A lock outranks expiry so a locked user keeps seeing the lockout.
     */
    @Transactional
    public PhoneChallenge reserveAttempt(UUID challengeId) {
        Instant now = clock.instant();
        PhoneChallenge challenge = lockedOpenChallenge(challengeId);
        if (challenge.isLockedAt(now)) {
            throw locked(Duration.between(now, challenge.getLockedUntil()));
        }
        boolean outOfAttempts = challenge.getAttempts() >= sms.maxAttempts();
        // A lapsed lockout means the code is spent: the user must ask for a new one (resend resets attempts).
        boolean lockoutLapsed = outOfAttempts && challenge.getLockedUntil() != null;
        if (challenge.isExpiredAt(now) || lockoutLapsed) {
            throw new ApiException(HttpStatus.GONE, CODE_EXPIRED, "That code has expired. Request a new one.");
        }
        if (outOfAttempts) {
            // The last allowed guess is still being checked; nobody gets another until it settles.
            throw locked(sms.lockout());
        }
        challenge.setAttempts(challenge.getAttempts() + 1);
        return challenges.save(challenge);
    }

    /** The code was wrong. Returns guesses left; on the last one starts the lockout and audits it. */
    @Transactional
    public int recordFailure(UUID challengeId, String ip) {
        Instant now = clock.instant();
        PhoneChallenge challenge = challenges.findByIdForUpdate(challengeId)
                .orElseThrow(PhoneChallengeStore::notFound);
        int left = Math.max(0, sms.maxAttempts() - challenge.getAttempts());
        if (left == 0 && !challenge.isLockedAt(now)) {
            Instant until = now.plus(sms.lockout());
            challenge.setLockedUntil(until);
            challenges.save(challenge);
            audit.record(OTP_LOCKED_EVENT, ip, Map.of(
                    "challengeId", challenge.getId().toString(),
                    "phone", Masking.phone(challenge.getPhoneE164()),
                    "lockedUntil", until.toString()));
            log.warn("Phone OTP locked, challenge={} phone={} until={}",
                    challenge.getId(), Masking.phone(challenge.getPhoneE164()), until);
        }
        return left;
    }

    /** Supabase could not answer, so the guess was never judged: give it back. */
    @Transactional
    public void refundAttempt(UUID challengeId) {
        challenges.findByIdForUpdate(challengeId).ifPresent(challenge -> {
            if (challenge.getAttempts() > 0 && !challenge.isVerified()) {
                challenge.setAttempts(challenge.getAttempts() - 1);
                challenges.save(challenge);
            }
        });
    }

    @Transactional
    public void markVerified(UUID challengeId) {
        PhoneChallenge challenge = challenges.findByIdForUpdate(challengeId)
                .orElseThrow(PhoneChallengeStore::notFound);
        challenge.setVerifiedAt(clock.instant());
        challenges.save(challenge);
    }

    /** A verified challenge is spent: it looks the same as one that never existed. */
    private PhoneChallenge lockedOpenChallenge(UUID challengeId) {
        PhoneChallenge challenge = challenges.findByIdForUpdate(challengeId)
                .orElseThrow(PhoneChallengeStore::notFound);
        if (challenge.isVerified()) {
            throw notFound();
        }
        return challenge;
    }

    private void requireNotLocked(String phoneE164, Instant now) {
        Optional<Instant> until = challenges.findActiveLockUntil(phoneE164, now);
        if (until.isPresent()) {
            throw new RateLimitedException(Duration.between(now, until.get()));
        }
    }

    private void requireUnderSendLimits(String phoneE164, String ip, Instant now) {
        Instant since = now.minus(WINDOW);
        if (sends.countByPhoneSince(phoneE164, since) >= sms.maxSendsPerPhonePerHour()) {
            throw new RateLimitedException(untilWindowFrees(sends.findOldestByPhoneSince(phoneE164, since), now));
        }
        if (sends.countByIpSince(ip, since) >= sms.maxSendsPerIpPerHour()) {
            throw new RateLimitedException(untilWindowFrees(sends.findOldestByIpSince(ip, since), now));
        }
    }

    private static Duration untilWindowFrees(Optional<Instant> oldestInWindow, Instant now) {
        return oldestInWindow.map(oldest -> Duration.between(now, oldest.plus(WINDOW))).orElse(WINDOW);
    }

    private static ApiException locked(Duration retryAfter) {
        return new ApiException(HttpStatus.LOCKED, LOCKED, "Too many wrong codes. Try again later.")
                .withRetryAfter(retryAfter);
    }

    private static NotFoundException notFound() {
        return new NotFoundException(CHALLENGE_NOT_FOUND, "No such sign-in request.");
    }
}
