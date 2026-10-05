package app.nichez.media.controller;

import app.nichez.media.dto.SignUploadRequest;
import app.nichez.media.dto.SignUploadResponse;
import app.nichez.media.service.UploadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Upload endpoints. Signed-in users only (the default for every route not listed as public). */
@RestController
class UploadController {

    private final UploadService uploads;

    UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/api/v1/uploads/sign")
    SignUploadResponse sign(@Valid @RequestBody SignUploadRequest body) {
        return uploads.sign(body.contentType(), body.bytes());
    }
}
