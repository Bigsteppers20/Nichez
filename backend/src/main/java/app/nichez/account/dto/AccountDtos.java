package app.nichez.account.dto;

import app.nichez.activity.dto.PushSettingsDtos;
import app.nichez.safety.dto.SafetyDtos;
import jakarta.validation.Valid;
import java.time.Instant;

/** Bodies for settings and the account lifecycle. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** Everything the Settings screen reads from the server. (Dark mode stays on the phone.) */
    public record SettingsResponse(PushSettingsDtos.Settings notifications, SafetyDtos.ContentPreferencesResponse content) {
    }

    /** Both parts are optional; what is left out stays as it is. */
    public record UpdateSettingsRequest(
            @Valid PushSettingsDtos.UpdateSettings notifications,
            @Valid SafetyDtos.UpdateContentPreferences content) {
    }

    /** When the account will be erased unless the person cancels. */
    public record DeletionScheduled(Instant deletionScheduledFor) {
    }

    /** The export was recorded; the download link is emailed when it is ready. */
    public record ExportRequested(String status) {
    }
}
