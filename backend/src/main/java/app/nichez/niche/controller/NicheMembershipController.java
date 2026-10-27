package app.nichez.niche.controller;

import app.nichez.common.CursorPage;
import app.nichez.niche.dto.NicheDtos.CreateNicheRequest;
import app.nichez.niche.dto.NicheDtos.CreatedNiche;
import app.nichez.niche.dto.NicheDtos.Group;
import app.nichez.niche.dto.NicheDtos.Membership;
import app.nichez.niche.dto.NicheDtos.MyNiche;
import app.nichez.niche.dto.NicheDtos.SetNichesRequest;
import app.nichez.niche.service.NicheService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The taxonomy, the caller's niches, joining and creating. Signed-in users only. */
@RestController
class NicheMembershipController {

    private final NicheService niches;

    NicheMembershipController(NicheService niches) {
        this.niches = niches;
    }

    @GetMapping("/api/v1/niches/taxonomy")
    CursorPage<Group> taxonomy() {
        return niches.taxonomy();
    }

    @GetMapping("/api/v1/me/niches")
    CursorPage<MyNiche> mine() {
        return niches.mine();
    }

    @PutMapping("/api/v1/me/niches")
    CursorPage<MyNiche> setPicks(@Valid @RequestBody SetNichesRequest body) {
        return niches.setPicks(body.nicheIds());
    }

    @PostMapping("/api/v1/niches")
    ResponseEntity<CreatedNiche> create(@Valid @RequestBody CreateNicheRequest body) {
        CreatedNiche created = niches.create(body.name());
        return ResponseEntity.created(URI.create("/api/v1/niches/" + created.id())).body(created);
    }

    @PostMapping("/api/v1/niches/{id}/join")
    Membership join(@PathVariable("id") UUID id) {
        return niches.join(id);
    }

    @GetMapping("/api/v1/niches/{id}/invite-link")
    Map<String, String> inviteLink(@PathVariable("id") UUID id) {
        return niches.inviteLink(id);
    }

    @DeleteMapping("/api/v1/niches/{id}/join")
    Membership leave(@PathVariable("id") UUID id) {
        return niches.leave(id);
    }
}
