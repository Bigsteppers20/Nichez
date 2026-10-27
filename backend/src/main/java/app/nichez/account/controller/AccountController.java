package app.nichez.account.controller;

import app.nichez.account.dto.AccountDtos.DeletionScheduled;
import app.nichez.account.dto.AccountDtos.ExportRequested;
import app.nichez.account.dto.AccountDtos.SettingsResponse;
import app.nichez.account.dto.AccountDtos.UpdateSettingsRequest;
import app.nichez.account.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Settings and the account lifecycle. Signed-in users only. */
@RestController
class AccountController {

    private final AccountService account;

    AccountController(AccountService account) {
        this.account = account;
    }

    @GetMapping("/api/v1/me/settings")
    SettingsResponse settings() {
        return account.settings();
    }

    @PatchMapping("/api/v1/me/settings")
    SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest body) {
        return account.update(body);
    }

    @PostMapping("/api/v1/me/deactivate")
    ResponseEntity<Void> deactivate(HttpServletRequest request) {
        account.deactivate(clientIp(request));
        return ResponseEntity.noContent().build();
    }

    /** 202: the account is hidden now and erased after the grace period. */
    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeletionScheduled delete(HttpServletRequest request) {
        return account.requestDeletion(clientIp(request));
    }

    @PostMapping("/api/v1/me/deletion/cancel")
    ResponseEntity<Void> cancelDeletion(HttpServletRequest request) {
        account.cancelDeletion(clientIp(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/me/data-export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ExportRequested export() {
        return account.requestExport();
    }

    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
