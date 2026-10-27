package app.nichez.safety.controller;

import app.nichez.common.CursorPage;
import app.nichez.safety.dto.SafetyDtos.AutoHideRequest;
import app.nichez.safety.dto.SafetyDtos.ContentPreferencesResponse;
import app.nichez.safety.dto.SafetyDtos.PostReportRequest;
import app.nichez.safety.dto.SafetyDtos.ReportItem;
import app.nichez.safety.dto.SafetyDtos.ReportReason;
import app.nichez.safety.dto.SafetyDtos.ReportReceived;
import app.nichez.safety.dto.SafetyDtos.ReportRequest;
import app.nichez.safety.dto.SafetyDtos.RestrictionItem;
import app.nichez.safety.dto.SafetyDtos.UpdateContentPreferences;
import app.nichez.safety.dto.SafetyDtos.VerdictRequest;
import app.nichez.safety.dto.SafetyDtos.VerdictResponse;
import app.nichez.safety.service.ModerationService;
import app.nichez.safety.service.ReportService;
import app.nichez.safety.service.SafetyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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

/** Blocking, muting, content filters, reports and the moderation queue. Signed-in users only. */
@RestController
class SafetyController {

    private final SafetyService safety;
    private final ReportService reports;
    private final ModerationService moderation;

    SafetyController(SafetyService safety, ReportService reports, ModerationService moderation) {
        this.safety = safety;
        this.reports = reports;
        this.moderation = moderation;
    }

    @PostMapping("/api/v1/users/{id}/block")
    ResponseEntity<Void> block(@PathVariable("id") UUID id) {
        safety.block(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/users/{id}/block")
    ResponseEntity<Void> unblock(@PathVariable("id") UUID id) {
        safety.unblock(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/users/{id}/mute")
    ResponseEntity<Void> mute(@PathVariable("id") UUID id) {
        safety.mute(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/users/{id}/mute")
    ResponseEntity<Void> unmute(@PathVariable("id") UUID id) {
        safety.unmute(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me/restrictions")
    CursorPage<RestrictionItem> restrictions(@RequestParam("type") String type,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return safety.restrictions(type, cursor, limit);
    }

    @GetMapping("/api/v1/me/content-preferences")
    ContentPreferencesResponse preferences() {
        return safety.preferences();
    }

    @PatchMapping("/api/v1/me/content-preferences")
    ContentPreferencesResponse updatePreferences(@Valid @RequestBody UpdateContentPreferences body) {
        return safety.update(body);
    }

    @PutMapping("/api/v1/me/preferences/auto-hide-reported")
    ContentPreferencesResponse autoHide(@Valid @RequestBody AutoHideRequest body) {
        return safety.setAutoHide(body.enabled());
    }

    @GetMapping("/api/v1/report-reasons")
    CursorPage<ReportReason> reasons() {
        return new CursorPage<>(reports.generalReasons(), null);
    }

    @GetMapping("/api/v1/niches/{id}/report-reasons")
    CursorPage<ReportReason> nicheReasons(@PathVariable("id") UUID id) {
        List<ReportReason> items = reports.reasonsFor(id);
        return new CursorPage<>(items, null);
    }

    @PostMapping("/api/v1/posts/{id}/report")
    ResponseEntity<ReportReceived> reportPost(@PathVariable("id") UUID id, @Valid @RequestBody PostReportRequest body) {
        ReportReceived received = reports.report("post", id, body.reasonId(), body.note());
        return ResponseEntity.created(URI.create("/api/v1/reports/" + received.id())).body(received);
    }

    @PostMapping("/api/v1/reports")
    ResponseEntity<ReportReceived> report(@Valid @RequestBody ReportRequest body) {
        ReportReceived received = reports.report(body.targetType(), body.targetId(), body.reasonId(), body.note());
        return ResponseEntity.created(URI.create("/api/v1/reports/" + received.id())).body(received);
    }

    @GetMapping("/api/v1/niches/{id}/mod-queue")
    CursorPage<ReportItem> nicheQueue(@PathVariable("id") UUID id,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return moderation.nicheQueue(id, tab, cursor, limit);
    }

    @GetMapping("/api/v1/communities/{id}/moderation/queue")
    CursorPage<ReportItem> communityQueue(@PathVariable("id") UUID id,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return moderation.communityQueue(id, tab, cursor, limit);
    }

    @PostMapping("/api/v1/reports/{id}/verdict")
    VerdictResponse verdict(@PathVariable("id") UUID id, @Valid @RequestBody VerdictRequest body,
            HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return moderation.decide(id, body.verdict(), address == null || address.isBlank() ? "unknown" : address);
    }
}
