package app.nichez.interaction.controller;

import app.nichez.common.CursorPage;
import app.nichez.interaction.dto.InteractionDtos.CommentReactionRequest;
import app.nichez.interaction.dto.InteractionDtos.CommentReactionResponse;
import app.nichez.interaction.dto.InteractionDtos.CommentResponse;
import app.nichez.interaction.dto.InteractionDtos.CreateCommentRequest;
import app.nichez.interaction.dto.InteractionDtos.PostReactionResponse;
import app.nichez.interaction.dto.InteractionDtos.ReactionRequest;
import app.nichez.interaction.service.CommentService;
import app.nichez.interaction.service.ReactionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reactions and comments. Signed-in users only (the default for every route not listed as public). */
@RestController
class InteractionController {

    private final ReactionService reactions;
    private final CommentService comments;

    InteractionController(ReactionService reactions, CommentService comments) {
        this.reactions = reactions;
        this.comments = comments;
    }

    @PutMapping("/api/v1/posts/{id}/reaction")
    PostReactionResponse react(@PathVariable("id") UUID id, @Valid @RequestBody ReactionRequest body) {
        return reactions.react(id, body.kind());
    }

    @DeleteMapping("/api/v1/posts/{id}/reaction")
    PostReactionResponse clearReaction(@PathVariable("id") UUID id) {
        return reactions.clear(id);
    }

    @GetMapping("/api/v1/posts/{id}/comments")
    CursorPage<CommentResponse> list(@PathVariable("id") UUID id,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return comments.list(id, sort, cursor, limit);
    }

    @PostMapping("/api/v1/posts/{id}/comments")
    ResponseEntity<CommentResponse> create(@PathVariable("id") UUID id,
            @Valid @RequestBody CreateCommentRequest body) {
        CommentResponse created = comments.create(id, body);
        return ResponseEntity.created(URI.create("/api/v1/comments/" + created.id())).body(created);
    }

    @DeleteMapping("/api/v1/comments/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        comments.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/comments/{id}/reaction")
    CommentReactionResponse reactToComment(@PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) CommentReactionRequest body) {
        return comments.react(id, body == null ? null : body.kind());
    }

    @DeleteMapping("/api/v1/comments/{id}/reaction")
    CommentReactionResponse clearCommentReaction(@PathVariable("id") UUID id) {
        return comments.clearReaction(id);
    }
}
