package app.nichez.profile.controller;

import app.nichez.profile.dto.ClaimHandleRequest;
import app.nichez.profile.dto.ClaimHandleResponse;
import app.nichez.profile.dto.HandleCheckResponse;
import app.nichez.profile.service.HandleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Handle endpoints. Both need a signed-in user (the default for every route not listed as public). */
@RestController
class HandleController {

    private final HandleService handles;

    HandleController(HandleService handles) {
        this.handles = handles;
    }

    @GetMapping("/api/v1/handles/check")
    HandleCheckResponse check(@RequestParam("handle") String handle) {
        return handles.check(handle);
    }

    @PutMapping("/api/v1/me/handle")
    ClaimHandleResponse claim(@Valid @RequestBody ClaimHandleRequest body) {
        return handles.claim(body.handle());
    }
}
