package app.nichez.collection.controller;

import app.nichez.collection.dto.CollectionDtos.CollectionRequest;
import app.nichez.collection.dto.CollectionDtos.CollectionResponse;
import app.nichez.collection.dto.CollectionDtos.SaveRequest;
import app.nichez.collection.dto.CollectionDtos.SaveState;
import app.nichez.collection.service.CollectionService;
import app.nichez.common.CursorPage;
import app.nichez.post.dto.PostResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Saves and collections. Signed-in users only. */
@RestController
class CollectionController {

    private final CollectionService collections;

    CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    @PutMapping("/api/v1/posts/{id}/save")
    SaveState save(@PathVariable("id") UUID id, @Valid @RequestBody(required = false) SaveRequest body) {
        return collections.save(id, body == null ? null : body.collectionId());
    }

    @DeleteMapping("/api/v1/posts/{id}/save")
    SaveState unsave(@PathVariable("id") UUID id) {
        return collections.unsave(id);
    }

    @GetMapping("/api/v1/me/saved")
    CursorPage<PostResponse> saved(@RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return collections.saved(cursor, limit);
    }

    @GetMapping("/api/v1/me/collections")
    CursorPage<CollectionResponse> list() {
        return collections.list();
    }

    @PostMapping("/api/v1/me/collections")
    ResponseEntity<CollectionResponse> create(@Valid @RequestBody CollectionRequest body) {
        CollectionResponse created = collections.create(body.name());
        return ResponseEntity.created(URI.create("/api/v1/me/collections/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/me/collections/{id}")
    CursorPage<PostResponse> posts(@PathVariable("id") UUID id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return collections.posts(id, cursor, limit);
    }

    @PatchMapping("/api/v1/collections/{id}")
    CollectionResponse rename(@PathVariable("id") UUID id, @Valid @RequestBody CollectionRequest body) {
        return collections.rename(id, body.name());
    }

    @DeleteMapping("/api/v1/collections/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        collections.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/collections/{id}/posts/{postId}")
    ResponseEntity<Void> addPost(@PathVariable("id") UUID id, @PathVariable("postId") UUID postId) {
        collections.addPost(id, postId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/collections/{id}/posts/{postId}")
    ResponseEntity<Void> removePost(@PathVariable("id") UUID id, @PathVariable("postId") UUID postId) {
        collections.removePost(id, postId);
        return ResponseEntity.noContent().build();
    }
}
