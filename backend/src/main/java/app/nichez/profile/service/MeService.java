package app.nichez.profile.service;

import app.nichez.common.ApiException;
import app.nichez.media.entity.Media;
import app.nichez.media.entity.MediaKind;
import app.nichez.media.service.MediaAttachments;
import app.nichez.niche.repository.NicheCatalogRepository;
import app.nichez.profile.dto.ProfileDtos.MeResponse;
import app.nichez.profile.dto.ProfileDtos.Onboarding;
import app.nichez.profile.dto.ProfileDtos.SessionResponse;
import app.nichez.profile.dto.ProfileDtos.UpdateProfileRequest;
import app.nichez.profile.dto.ProfileDtos.UserProfileResponse;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.repository.UserRepository.ProfileRow;
import app.nichez.config.AccountProperties;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in person's own account: who they are, how far sign-up got, editing their profile and
 * photos. Who is asking always comes from the verified token ({@link CurrentUser}).
 */
@Service
public class MeService {

    public static final String NOTHING_TO_UPDATE = "NOTHING_TO_UPDATE";

    private final UserRepository users;
    private final ProfileRepository profiles;
    private final NicheCatalogRepository niches;
    private final ProfileViews views;
    private final HandleService handles;
    private final MediaAttachments attachments;
    private final AccountProperties account;
    private final Clock clock;

    MeService(UserRepository users, ProfileRepository profiles, NicheCatalogRepository niches, ProfileViews views,
            HandleService handles, MediaAttachments attachments, AccountProperties account, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.niches = niches;
        this.views = views;
        this.handles = handles;
        this.attachments = attachments;
        this.account = account;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        UUID me = CurrentUser.id();
        ProfileRow row = users.profile(me).orElseGet(() -> ProfileViews.blank(me));
        return new MeResponse(views.of(row, false, true), onboardingOf(row, me));
    }

    /**
     * What the app needs at boot (drives the router guard). A deactivated account is brought back
     * here: deactivating hides the account only until the next sign-in.
     */
    @Transactional
    public SessionResponse session() {
        UUID me = CurrentUser.id();
        ProfileRow row = users.profile(me).orElseGet(() -> ProfileViews.blank(me));
        boolean reactivated = false;
        if (row.deactivatedAt() != null) {
            users.setAccountState(me, null, row.deletionRequestedAt(), clock.instant());
            reactivated = true;
        }
        Onboarding onboarding = onboardingOf(row, me);
        Instant deletion = row.deletionRequestedAt() == null ? null
                : row.deletionRequestedAt().plus(account.deletionGrace());
        return new SessionResponse(me, onboarding.hasHandle(), onboarding.hasNiches(), onboarding.hasPhoto(),
                onboarding.onboarded(), CurrentUser.isAdmin(), reactivated, deletion);
    }

    /**
     * Name, bio and handle. The handle goes through the same rules and race protection as claiming
     * one, which is why this is deliberately not one transaction (see {@link HandleService#claim}).
     */
    public UserProfileResponse update(UpdateProfileRequest request) {
        UUID me = CurrentUser.id();
        if (request.name() == null && request.bio() == null && request.handle() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NOTHING_TO_UPDATE, "Send a name, bio or handle to change.");
        }
        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        if (request.handle() != null) {
            handles.claim(request.handle());
        }
        if (request.name() != null || request.bio() != null) {
            users.updateText(me, request.name() == null ? null : request.name().strip(),
                    request.bio() == null ? null : request.bio().strip(), now);
        }
        return view(me);
    }

    @Transactional
    public UserProfileResponse setAvatar(UUID mediaId) {
        UUID me = CurrentUser.id();
        Media image = attachments.attach(mediaId, me, Set.of(MediaKind.IMAGE));
        profiles.ensureExists(me, clock.instant());
        users.setAvatar(me, image.getId(), clock.instant());
        profiles.markOnboardedIfComplete(me, HandleService.MIN_NICHES_TO_ONBOARD, clock.instant());
        return view(me);
    }

    @Transactional
    public UserProfileResponse removeAvatar() {
        UUID me = CurrentUser.id();
        users.setAvatar(me, null, clock.instant());
        return view(me);
    }

    @Transactional
    public UserProfileResponse setCover(UUID mediaId) {
        UUID me = CurrentUser.id();
        Media image = attachments.attach(mediaId, me, Set.of(MediaKind.IMAGE));
        profiles.ensureExists(me, clock.instant());
        users.setCover(me, image.getId(), clock.instant());
        return view(me);
    }

    @Transactional
    public UserProfileResponse removeCover() {
        UUID me = CurrentUser.id();
        users.setCover(me, null, clock.instant());
        return view(me);
    }

    private UserProfileResponse view(UUID me) {
        return views.of(users.profile(me).orElseGet(() -> ProfileViews.blank(me)), false, true);
    }

    private Onboarding onboardingOf(ProfileRow row, UUID me) {
        return new Onboarding(row.handle() != null,
                niches.joinedCount(me) >= HandleService.MIN_NICHES_TO_ONBOARD,
                row.hasAvatar(),
                row.onboardedAt() != null);
    }
}
