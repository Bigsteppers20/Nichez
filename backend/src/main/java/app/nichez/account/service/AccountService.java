package app.nichez.account.service;

import app.nichez.account.dto.AccountDtos.DeletionScheduled;
import app.nichez.account.dto.AccountDtos.ExportRequested;
import app.nichez.account.dto.AccountDtos.SettingsResponse;
import app.nichez.account.dto.AccountDtos.UpdateSettingsRequest;
import app.nichez.account.repository.AccountRepository;
import app.nichez.activity.dto.PushSettingsDtos;
import app.nichez.activity.service.ActivityService;
import app.nichez.common.AuditService;
import app.nichez.common.ConflictException;
import app.nichez.config.AccountProperties;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.repository.UserRepository.ProfileRow;
import app.nichez.safety.dto.SafetyDtos.ContentPreferencesResponse;
import app.nichez.safety.service.SafetyService;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings and the account lifecycle: hide the account until the next sign-in, ask for it to be
 * deleted (hidden at once, erased after the grace period, cancellable until then), and ask for a copy
 * of the person's data. Who is asking always comes from the token.
 */
@Service
public class AccountService {

    public static final String EXPORT_ALREADY_REQUESTED = "EXPORT_ALREADY_REQUESTED";
    public static final String NO_DELETION_PENDING = "NO_DELETION_PENDING";
    public static final String ACCOUNT_DEACTIVATED_EVENT = "ACCOUNT_DEACTIVATED";
    public static final String ACCOUNT_DELETION_REQUESTED_EVENT = "ACCOUNT_DELETION_REQUESTED";
    public static final String ACCOUNT_DELETION_CANCELLED_EVENT = "ACCOUNT_DELETION_CANCELLED";

    private final ActivityService activity;
    private final SafetyService safety;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final AccountRepository accounts;
    private final AccountProperties properties;
    private final AuditService audit;
    private final Clock clock;

    AccountService(ActivityService activity, SafetyService safety, UserRepository users, ProfileRepository profiles,
            AccountRepository accounts, AccountProperties properties, AuditService audit, Clock clock) {
        this.activity = activity;
        this.safety = safety;
        this.users = users;
        this.profiles = profiles;
        this.accounts = accounts;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsResponse settings() {
        return new SettingsResponse(activity.settings(), safety.preferences());
    }

    @Transactional
    public SettingsResponse update(UpdateSettingsRequest request) {
        PushSettingsDtos.Settings notifications = request.notifications() == null
                ? activity.settings() : activity.update(request.notifications());
        ContentPreferencesResponse content = request.content() == null
                ? safety.preferences() : safety.update(request.content());
        return new SettingsResponse(notifications, content);
    }

    /** Hides the account until the person next signs in. */
    @Transactional
    public void deactivate(String ipAddress) {
        UUID me = CurrentUser.id();
        ProfileRow row = users.profile(me).orElse(null);
        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        users.setAccountState(me, now, row == null ? null : row.deletionRequestedAt(), now);
        audit.record(ACCOUNT_DEACTIVATED_EVENT, ipAddress, Map.of("userId", me.toString()));
    }

    /**
     * Hides the account now and schedules its erasure. Asking again keeps the first date. Until the
     * grace period ends the person can still cancel.
     */
    @Transactional
    public DeletionScheduled requestDeletion(String ipAddress) {
        UUID me = CurrentUser.id();
        Instant now = clock.instant();
        profiles.ensureExists(me, now);
        ProfileRow row = users.profile(me).orElseThrow();
        Instant requested = row.deletionRequestedAt() != null ? row.deletionRequestedAt() : now;
        if (row.deletionRequestedAt() == null) {
            // Deletion hides the account on its own; deactivation stays as the person left it.
            users.setAccountState(me, row.deactivatedAt(), requested, now);
            audit.record(ACCOUNT_DELETION_REQUESTED_EVENT, ipAddress, Map.of("userId", me.toString()));
        }
        return new DeletionScheduled(requested.plus(properties.deletionGrace()));
    }

    /** Cancels a pending deletion and brings the account back. */
    @Transactional
    public void cancelDeletion(String ipAddress) {
        UUID me = CurrentUser.id();
        ProfileRow row = users.profile(me).orElse(null);
        if (row == null || row.deletionRequestedAt() == null) {
            throw new ConflictException(NO_DELETION_PENDING, "There is no deletion waiting to be cancelled.");
        }
        users.setAccountState(me, null, null, clock.instant());
        audit.record(ACCOUNT_DELETION_CANCELLED_EVENT, ipAddress, Map.of("userId", me.toString()));
    }

    /** Records a request for a copy of the person's data. Fulfilment is a separate job. */
    @Transactional
    public ExportRequested requestExport() {
        UUID me = CurrentUser.id();
        profiles.ensureExists(me, clock.instant());
        if (!accounts.requestExport(me, clock.instant())) {
            throw new ConflictException(EXPORT_ALREADY_REQUESTED, "You already asked for a copy of your data.");
        }
        return new ExportRequested("requested");
    }
}
