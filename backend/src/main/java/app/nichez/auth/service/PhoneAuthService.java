package app.nichez.auth.service;

import app.nichez.auth.dto.ResendPhoneRequest;
import app.nichez.auth.dto.ResendPhoneResponse;
import app.nichez.auth.dto.SessionDto;
import app.nichez.auth.dto.StartPhoneRequest;
import app.nichez.auth.dto.StartPhoneResponse;
import app.nichez.auth.dto.VerifyPhoneRequest;
import app.nichez.auth.dto.VerifyPhoneResponse;
import app.nichez.auth.entity.PhoneChallenge;
import app.nichez.auth.service.PhoneNumberParser.ParsedPhone;
import app.nichez.auth.service.SupabaseAuthClient.VerifiedSession;
import app.nichez.common.ApiException;
import app.nichez.common.Masking;
import app.nichez.config.SmsAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Phone OTP sign-in. These endpoints are public, so every protection lives here:
 * number validation, country allow-list, send limits, attempt limits and lockout.
 *
 * <p>Nothing here asks whether the phone already has an account, so /start answers
 * the same for new and existing numbers. Whether the user is new is only learned
 * after a correct code, from Supabase's own answer.
 */
@Service
public class PhoneAuthService {

    public static final String COUNTRY_NOT_SUPPORTED = "COUNTRY_NOT_SUPPORTED";
    public static final String WRONG_CODE = "WRONG_CODE";

    private static final Logger log = LoggerFactory.getLogger(PhoneAuthService.class);

    private final PhoneNumberParser parser;
    private final PhoneChallengeStore store;
    private final SupabaseAuthClient supabase;
    private final SmsAuthProperties sms;

    PhoneAuthService(PhoneNumberParser parser, PhoneChallengeStore store, SupabaseAuthClient supabase,
            SmsAuthProperties sms) {
        this.parser = parser;
        this.store = store;
        this.supabase = supabase;
        this.sms = sms;
    }

    public StartPhoneResponse start(StartPhoneRequest request, String ip) {
        ParsedPhone phone = parser.parse(request.dialCode(), request.nationalNumber());
        if (!sms.allowedCountries().contains(phone.region())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, COUNTRY_NOT_SUPPORTED,
                    "Phone sign-in is not available in that country yet.");
        }
        PhoneChallenge challenge = store.begin(phone.e164(), ip);
        log.info("Phone OTP start, challenge={} phone={} region={} ip={}",
                challenge.getId(), Masking.phone(phone.e164()), phone.region(), ip);
        supabase.sendOtp(phone.e164());
        return new StartPhoneResponse(challenge.getId(), resendAfterSeconds());
    }

    public ResendPhoneResponse resend(ResendPhoneRequest request, String ip) {
        PhoneChallenge challenge = store.beginResend(request.challengeId(), ip);
        log.info("Phone OTP resend, challenge={} phone={} ip={}",
                challenge.getId(), Masking.phone(challenge.getPhoneE164()), ip);
        supabase.sendOtp(challenge.getPhoneE164());
        return new ResendPhoneResponse(resendAfterSeconds());
    }

    public VerifyPhoneResponse verify(VerifyPhoneRequest request, String ip) {
        PhoneChallenge challenge = store.reserveAttempt(request.challengeId());
        VerifiedSession session;
        try {
            session = supabase.verify(challenge.getPhoneE164(), request.code());
        } catch (AuthProviderException e) {
            store.refundAttempt(challenge.getId());
            throw e;
        }
        if (session == null) {
            int attemptsLeft = store.recordFailure(challenge.getId(), ip);
            log.info("Phone OTP wrong code, challenge={} phone={} code={} attemptsLeft={}",
                    challenge.getId(), Masking.phone(challenge.getPhoneE164()), Masking.code(), attemptsLeft);
            throw new ApiException(HttpStatus.BAD_REQUEST, WRONG_CODE, "That code is not correct.")
                    .withProperty("attemptsLeft", attemptsLeft);
        }
        store.markVerified(challenge.getId());
        log.info("Phone OTP verified, challenge={} phone={} newUser={}",
                challenge.getId(), Masking.phone(challenge.getPhoneE164()), session.newUser());
        return new VerifyPhoneResponse(
                new SessionDto(session.accessToken(), session.refreshToken(), session.expiresAt()),
                session.newUser());
    }

    private int resendAfterSeconds() {
        return (int) sms.resendCooldown().toSeconds();
    }
}
