package app.nichez.sharing.controller;

import app.nichez.common.CursorPage;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.sharing.dto.SharingDtos.CrossPostRequest;
import app.nichez.sharing.dto.SharingDtos.CrossPostResponse;
import app.nichez.sharing.dto.SharingDtos.LinkResponse;
import app.nichez.sharing.dto.SharingDtos.PublicPost;
import app.nichez.sharing.dto.SharingDtos.ReshareResponse;
import app.nichez.sharing.dto.SharingDtos.ShareCount;
import app.nichez.sharing.dto.SharingDtos.ShareRequest;
import app.nichez.sharing.service.SharingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Sharing endpoints. Signed-in users only, except {@code GET /links/{code}}, which is public on purpose. */
@RestController
class SharingController {

    private final SharingService sharing;

    SharingController(SharingService sharing) {
        this.sharing = sharing;
    }

    @PostMapping("/api/v1/posts/{id}/shares")
    ShareCount share(@PathVariable("id") UUID id, @Valid @RequestBody ShareRequest body) {
        return sharing.recordShare(id, body.channel());
    }

    @GetMapping("/api/v1/posts/{id}/link")
    LinkResponse link(@PathVariable("id") UUID id) {
        return sharing.link(id);
    }

    @PostMapping("/api/v1/posts/{id}/reshares")
    ReshareResponse reshare(@PathVariable("id") UUID id) {
        return sharing.reshare(id);
    }

    @PostMapping("/api/v1/posts/{id}/reshare")
    CrossPostResponse crossPost(@PathVariable("id") UUID id, @Valid @RequestBody CrossPostRequest body) {
        return sharing.crossPost(id, body.nicheId());
    }

    @PostMapping("/api/v1/posts/{id}/not-interested")
    ResponseEntity<Void> notInterested(@PathVariable("id") UUID id) {
        sharing.notInterested(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me/share-targets")
    CursorPage<PersonItem> shareTargets() {
        return sharing.shareTargets();
    }

    /** Public: resolves a share link for the app's deep link and the web fallback. Rate limited per address. */
    @GetMapping("/api/v1/links/{code}")
    PublicPost resolve(@PathVariable("code") String code, HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return sharing.resolve(code, address == null || address.isBlank() ? "unknown" : address);
    }
}
