package app.nichez.graph.controller;

import app.nichez.common.CursorPage;
import app.nichez.graph.dto.GraphDtos.FollowManyRequest;
import app.nichez.graph.dto.GraphDtos.FollowManyResponse;
import app.nichez.graph.dto.GraphDtos.FollowState;
import app.nichez.graph.dto.GraphDtos.PersonItem;
import app.nichez.graph.dto.GraphDtos.SuggestedCreator;
import app.nichez.graph.service.FollowService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Follow graph endpoints. Signed-in users only. */
@RestController
class FollowController {

    private final FollowService follows;

    FollowController(FollowService follows) {
        this.follows = follows;
    }

    @PostMapping("/api/v1/users/{id}/follow")
    FollowState follow(@PathVariable("id") UUID id) {
        return follows.follow(id);
    }

    @DeleteMapping("/api/v1/users/{id}/follow")
    FollowState unfollow(@PathVariable("id") UUID id) {
        return follows.unfollow(id);
    }

    @PostMapping("/api/v1/me/follows")
    FollowManyResponse followMany(@Valid @RequestBody FollowManyRequest body) {
        return follows.followMany(body.userIds());
    }

    @GetMapping("/api/v1/users/{id}/followers")
    CursorPage<PersonItem> people(@PathVariable("id") UUID id,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return follows.people(id, filter, q, cursor, limit);
    }

    @GetMapping("/api/v1/creators/suggested")
    CursorPage<SuggestedCreator> suggested() {
        return follows.suggested();
    }
}
