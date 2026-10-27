package app.nichez.post.controller;

import app.nichez.common.CursorPage;
import app.nichez.post.dto.PostExtrasDtos.DraftRequest;
import app.nichez.post.dto.PostExtrasDtos.DraftResponse;
import app.nichez.post.dto.PostExtrasDtos.DraftsPage;
import app.nichez.post.dto.PostExtrasDtos.MyAnalytics;
import app.nichez.post.dto.PostExtrasDtos.PostAnalytics;
import app.nichez.post.dto.PostExtrasDtos.PostStatusResponse;
import app.nichez.post.dto.PostExtrasDtos.PostTarget;
import app.nichez.post.dto.PostExtrasDtos.StoryGroup;
import app.nichez.post.dto.PostExtrasDtos.StoryItem;
import app.nichez.post.dto.PostExtrasDtos.StoryRequest;
import app.nichez.post.service.DraftService;
import app.nichez.post.service.PostInsightsService;
import app.nichez.post.service.StoryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Where to post, post status and analytics, drafts and stories. Signed-in users only. */
@RestController
class PostExtrasController {

    private final PostInsightsService insights;
    private final DraftService drafts;
    private final StoryService stories;

    PostExtrasController(PostInsightsService insights, DraftService drafts, StoryService stories) {
        this.insights = insights;
        this.drafts = drafts;
        this.stories = stories;
    }

    @GetMapping("/api/v1/me/post-targets")
    CursorPage<PostTarget> targets() {
        return insights.targets();
    }

    @GetMapping("/api/v1/posts/{id}/status")
    PostStatusResponse status(@PathVariable("id") UUID id) {
        return insights.status(id);
    }

    @GetMapping("/api/v1/posts/{id}/analytics")
    PostAnalytics analytics(@PathVariable("id") UUID id) {
        return insights.postAnalytics(id);
    }

    @GetMapping("/api/v1/me/analytics")
    MyAnalytics myAnalytics(@RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return insights.myAnalytics(cursor, limit);
    }

    @GetMapping("/api/v1/me/drafts")
    DraftsPage listDrafts(@RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return drafts.list(cursor, limit);
    }

    @PostMapping("/api/v1/me/drafts")
    ResponseEntity<DraftResponse> createDraft(@Valid @RequestBody DraftRequest body) {
        DraftResponse created = drafts.create(body);
        return ResponseEntity.created(URI.create("/api/v1/me/drafts")).body(created);
    }

    @DeleteMapping("/api/v1/drafts/{id}")
    ResponseEntity<Void> deleteDraft(@PathVariable("id") UUID id) {
        drafts.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/stories")
    ResponseEntity<StoryItem> createStory(@Valid @RequestBody StoryRequest body) {
        StoryItem created = stories.create(body);
        return ResponseEntity.created(URI.create("/api/v1/stories/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/stories")
    CursorPage<StoryGroup> storyRow() {
        return stories.feed();
    }

    @GetMapping("/api/v1/users/{id}/stories")
    StoryGroup storiesOf(@PathVariable("id") UUID id) {
        return stories.of(id);
    }

    @PostMapping("/api/v1/stories/{id}/views")
    ResponseEntity<Void> viewStory(@PathVariable("id") UUID id) {
        stories.view(id);
        return ResponseEntity.noContent().build();
    }
}
