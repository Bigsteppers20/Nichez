package app.nichez.security;

import app.nichez.common.ConflictException;
import app.nichez.common.IdempotencyMismatchException;
import app.nichez.common.NotFoundException;
import app.nichez.common.RateLimitedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only under the test profile, to exercise security and the error handler. */
@RestController
@Profile("test")
@RequestMapping("/api/v1")
class SecurityTestController {

    record Body(@NotBlank @Size(max = 5) String name) {
    }

    @GetMapping("/test/me")
    Map<String, Object> me() {
        return Map.of("id", CurrentUser.id().toString(), "admin", CurrentUser.isAdmin());
    }

    @GetMapping("/admin/test")
    Map<String, String> admin() {
        return Map.of("status", "admin ok");
    }

    @PostMapping("/test/validate")
    Map<String, String> validate(@Valid @RequestBody Body body) {
        return Map.of("name", body.name());
    }

    @GetMapping("/test/not-found")
    void notFound() {
        throw new NotFoundException("POST_NOT_FOUND", "Post not found.");
    }

    @GetMapping("/test/conflict")
    void conflict() {
        throw new ConflictException("ALREADY_SAVED", "Already saved.");
    }

    @GetMapping("/test/idempotency")
    void idempotency() {
        throw new IdempotencyMismatchException();
    }

    @GetMapping("/test/rate-limit")
    void rateLimit() {
        throw new RateLimitedException(Duration.ofSeconds(30));
    }

    @GetMapping("/test/boom")
    void boom() {
        throw new IllegalStateException("secret-internal-detail jdbc:postgresql://db/prod");
    }
}
