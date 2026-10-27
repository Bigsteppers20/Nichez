package app.nichez.auth.controller;

import app.nichez.auth.service.SupabaseAuthClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ends the caller's session. Signed-in users only; the token was already verified by the security chain. */
@RestController
class LogoutController {

    private static final String BEARER = "Bearer ";

    private final SupabaseAuthClient supabase;

    LogoutController(SupabaseAuthClient supabase) {
        this.supabase = supabase;
    }

    /** 204. The session is ended at Supabase; the app also discards its tokens and removes its push token. */
    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            supabase.logout(header.substring(BEARER.length()));
        }
        return ResponseEntity.noContent().build();
    }
}
