package app.nichez.stream.controller;

import app.nichez.interaction.dto.InteractionDtos.ViewsRequest;
import app.nichez.interaction.dto.InteractionDtos.ViewsResponse;
import app.nichez.stream.service.ViewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Playback reports. Signed-in users only. */
@RestController
class ViewController {

    private final ViewService views;

    ViewController(ViewService views) {
        this.views = views;
    }

    /** 202: the reports are accepted; the server decides which count. */
    @PostMapping("/api/v1/views")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ViewsResponse report(@Valid @RequestBody ViewsRequest body) {
        return views.report(body);
    }
}
