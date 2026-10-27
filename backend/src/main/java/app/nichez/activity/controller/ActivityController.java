package app.nichez.activity.controller;

import app.nichez.activity.dto.ActivityItemResponse;
import app.nichez.activity.dto.PushSettingsDtos;
import app.nichez.activity.service.ActivityService;
import app.nichez.common.CursorPage;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Activity, unread badge, push devices and push settings. Signed-in users only. */
@RestController
class ActivityController {

    private final ActivityService activity;

    ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    @GetMapping("/api/v1/me/activity")
    CursorPage<ActivityItemResponse> list(@RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return activity.list(filter, cursor, limit);
    }

    @GetMapping("/api/v1/me/activity/unread-count")
    PushSettingsDtos.UnreadCount unreadCount() {
        return activity.unreadCount();
    }

    @PostMapping("/api/v1/me/activity/read")
    ResponseEntity<Void> readAll() {
        activity.markAllRead();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/notifications/{id}/read")
    ResponseEntity<Void> read(@PathVariable("id") UUID id) {
        activity.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/me/push-token")
    ResponseEntity<Void> registerDevice(@Valid @RequestBody PushSettingsDtos.RegisterDevice body) {
        activity.registerDevice(body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/devices/{token}")
    ResponseEntity<Void> removeDevice(@PathVariable("token") String token) {
        activity.removeDevice(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me/notification-settings")
    PushSettingsDtos.Settings settings() {
        return activity.settings();
    }

    @PatchMapping("/api/v1/me/notification-settings")
    PushSettingsDtos.Settings patch(@Valid @RequestBody PushSettingsDtos.UpdateSettings body) {
        return activity.update(body);
    }

    /** The same update as the PATCH above, under the path {@code API.md} lists for the Settings screen. */
    @PutMapping("/api/v1/me/preferences/push")
    PushSettingsDtos.Settings put(@Valid @RequestBody PushSettingsDtos.UpdateSettings body) {
        return activity.update(body);
    }
}
