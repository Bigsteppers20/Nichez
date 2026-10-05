package app.nichez.auth.controller;

import app.nichez.auth.dto.ResendPhoneRequest;
import app.nichez.auth.dto.ResendPhoneResponse;
import app.nichez.auth.dto.StartPhoneRequest;
import app.nichez.auth.dto.StartPhoneResponse;
import app.nichez.auth.dto.VerifyPhoneRequest;
import app.nichez.auth.dto.VerifyPhoneResponse;
import app.nichez.auth.service.PhoneAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, rate-limited phone sign-in (see SecurityConfig.PUBLIC_PATHS).
 * The voice-call fallback {@code /auth/phone/call} is deliberately not built (decision 12).
 *
 * <p>The client IP is the servlet's remote address. Behind a proxy, set
 * {@code server.forward-headers-strategy} so that address is the real client, not the proxy.
 */
@RestController
@RequestMapping("/api/v1/auth/phone")
class PhoneAuthController {

    private final PhoneAuthService service;

    PhoneAuthController(PhoneAuthService service) {
        this.service = service;
    }

    @PostMapping("/start")
    StartPhoneResponse start(@Valid @RequestBody StartPhoneRequest body, HttpServletRequest request) {
        return service.start(body, clientIp(request));
    }

    @PostMapping("/verify")
    VerifyPhoneResponse verify(@Valid @RequestBody VerifyPhoneRequest body, HttpServletRequest request) {
        return service.verify(body, clientIp(request));
    }

    @PostMapping("/resend")
    ResendPhoneResponse resend(@Valid @RequestBody ResendPhoneRequest body, HttpServletRequest request) {
        return service.resend(body, clientIp(request));
    }

    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
