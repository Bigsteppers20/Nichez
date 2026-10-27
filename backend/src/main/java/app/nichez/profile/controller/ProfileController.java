package app.nichez.profile.controller;

import app.nichez.common.CursorPage;
import app.nichez.post.dto.PostResponse;
import app.nichez.profile.dto.ProfileDtos.MeResponse;
import app.nichez.profile.dto.ProfileDtos.SessionResponse;
import app.nichez.profile.dto.ProfileDtos.SetImageRequest;
import app.nichez.profile.dto.ProfileDtos.UpdateProfileRequest;
import app.nichez.profile.dto.ProfileDtos.UserProfileResponse;
import app.nichez.profile.service.MeService;
import app.nichez.profile.service.ProfileQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's account and everyone's profiles. Signed-in users only. */
@RestController
class ProfileController {

    private final MeService me;
    private final ProfileQueryService profiles;

    ProfileController(MeService me, ProfileQueryService profiles) {
        this.me = me;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/me")
    MeResponse me() {
        return me.me();
    }

    @GetMapping("/api/v1/auth/session")
    SessionResponse session() {
        return me.session();
    }

    @PatchMapping("/api/v1/me")
    UserProfileResponse patch(@Valid @RequestBody UpdateProfileRequest body) {
        return me.update(body);
    }

    /** The same update as {@code PATCH /me}, under the path {@code API.md} lists for Edit profile. */
    @PutMapping("/api/v1/me/profile")
    UserProfileResponse put(@Valid @RequestBody UpdateProfileRequest body) {
        return me.update(body);
    }

    @PostMapping("/api/v1/me/avatar")
    UserProfileResponse setAvatar(@Valid @RequestBody SetImageRequest body) {
        return me.setAvatar(body.mediaId());
    }

    @DeleteMapping("/api/v1/me/avatar")
    UserProfileResponse removeAvatar() {
        return me.removeAvatar();
    }

    @PutMapping("/api/v1/me/cover")
    UserProfileResponse setCover(@Valid @RequestBody SetImageRequest body) {
        return me.setCover(body.mediaId());
    }

    @DeleteMapping("/api/v1/me/cover")
    UserProfileResponse removeCover() {
        return me.removeCover();
    }

    @GetMapping("/api/v1/users/{handleOrId}")
    UserProfileResponse profile(@PathVariable("handleOrId") String handleOrId) {
        return profiles.get(handleOrId);
    }

    @GetMapping("/api/v1/users/{id}/posts")
    CursorPage<PostResponse> posts(@PathVariable("id") UUID id,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return profiles.posts(id, tab, cursor, limit);
    }
}
