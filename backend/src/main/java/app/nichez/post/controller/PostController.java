package app.nichez.post.controller;

import app.nichez.post.dto.CreatePostRequest;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.service.PostCreationService;
import app.nichez.post.service.PostQueryService;
import app.nichez.post.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Post endpoints. Signed-in users only (the default for every route not listed as public). */
@RestController
class PostController {

    private final PostService posts;
    private final PostCreationService creation;
    private final PostQueryService queries;

    PostController(PostService posts, PostCreationService creation, PostQueryService queries) {
        this.posts = posts;
        this.creation = creation;
        this.queries = queries;
    }

    /** 201 with the new post, status {@code processing}. The checks are in {@link PostCreationService#create}. */
    @PostMapping("/api/v1/posts")
    ResponseEntity<PostResponse> create(@Valid @RequestBody CreatePostRequest body, HttpServletRequest request) {
        PostResponse post = creation.create(body, clientIp(request));
        return ResponseEntity.created(URI.create("/api/v1/posts/" + post.id())).body(post);
    }

    /** Who may see which post is decided in {@link PostQueryService#get}. */
    @GetMapping("/api/v1/posts/{id}")
    PostResponse get(@PathVariable("id") UUID id) {
        return queries.get(id);
    }

    /** 204 on success. Who may delete is decided in {@link PostService#delete}. */
    @DeleteMapping("/api/v1/posts/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") UUID id, HttpServletRequest request) {
        posts.delete(id, clientIp(request));
        return ResponseEntity.noContent().build();
    }

    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
