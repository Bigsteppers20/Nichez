package app.nichez.niche.controller;

import app.nichez.common.CursorPage;
import app.nichez.niche.dto.CommunityDtos.ChangeRoleRequest;
import app.nichez.niche.dto.CommunityDtos.CommunityResponse;
import app.nichez.niche.dto.CommunityDtos.CreateCommunityRequest;
import app.nichez.niche.dto.CommunityDtos.InviteRequest;
import app.nichez.niche.dto.CommunityDtos.InviteResult;
import app.nichez.niche.dto.CommunityDtos.JoinState;
import app.nichez.niche.dto.CommunityDtos.MemberItem;
import app.nichez.niche.dto.CommunityDtos.RulesResponse;
import app.nichez.niche.service.CommunityService;
import app.nichez.post.dto.PostResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Communities. Signed-in users only; what each person may do is decided in the service. */
@RestController
class CommunityController {

    private final CommunityService communities;

    CommunityController(CommunityService communities) {
        this.communities = communities;
    }

    @GetMapping("/api/v1/communities")
    CursorPage<CommunityResponse> list(@RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return communities.list(tab, q, cursor, limit);
    }

    /** The same as {@code GET /communities?tab=discover}, under the path {@code API.md} lists for Discover. */
    @GetMapping("/api/v1/communities/discover")
    CursorPage<CommunityResponse> discover(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return communities.list("discover", q, cursor, limit);
    }

    @PostMapping("/api/v1/communities")
    ResponseEntity<CommunityResponse> create(@Valid @RequestBody CreateCommunityRequest body) {
        CommunityResponse created = communities.create(body);
        return ResponseEntity.created(URI.create("/api/v1/communities/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/communities/{id}")
    CommunityResponse get(@PathVariable("id") UUID id) {
        return communities.get(id);
    }

    @GetMapping("/api/v1/communities/{id}/posts")
    CursorPage<PostResponse> posts(@PathVariable("id") UUID id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return communities.posts(id, cursor, limit);
    }

    @GetMapping("/api/v1/communities/{id}/rules")
    RulesResponse rules(@PathVariable("id") UUID id) {
        return communities.rules(id);
    }

    @PostMapping("/api/v1/communities/{id}/join")
    JoinState join(@PathVariable("id") UUID id) {
        return communities.join(id);
    }

    @DeleteMapping("/api/v1/communities/{id}/join")
    JoinState leave(@PathVariable("id") UUID id) {
        return communities.leave(id);
    }

    @GetMapping("/api/v1/communities/{id}/members")
    CursorPage<MemberItem> members(@PathVariable("id") UUID id,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return communities.members(id, filter, cursor, limit);
    }

    @PatchMapping("/api/v1/communities/{id}/members/{userId}")
    MemberItem changeRole(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId,
            @Valid @RequestBody ChangeRoleRequest body) {
        return communities.changeRole(id, userId, body.role());
    }

    @DeleteMapping("/api/v1/communities/{id}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        communities.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/communities/{id}/requests/{userId}/approve")
    MemberItem approve(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        return communities.approve(id, userId);
    }

    @PostMapping("/api/v1/communities/{id}/requests/{userId}/deny")
    ResponseEntity<Void> deny(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        communities.deny(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/communities/{id}/invites")
    InviteResult invite(@PathVariable("id") UUID id, @Valid @RequestBody InviteRequest body) {
        return communities.invite(id, body.userIds());
    }

    @GetMapping("/api/v1/communities/{id}/invite-link")
    Map<String, String> inviteLink(@PathVariable("id") UUID id) {
        return communities.inviteLink(id);
    }
}
