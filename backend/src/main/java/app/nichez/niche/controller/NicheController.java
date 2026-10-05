package app.nichez.niche.controller;

import app.nichez.niche.dto.NicheStatsResponse;
import app.nichez.niche.service.NicheStatsService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Niche endpoints. Signed-in users only (the default for every route not listed as public). */
@RestController
class NicheController {

    private final NicheStatsService stats;

    NicheController(NicheStatsService stats) {
        this.stats = stats;
    }

    /** A non-UUID id fails conversion: 400 BAD_REQUEST. An unknown one is 404 NICHE_NOT_FOUND. */
    @GetMapping("/api/v1/niches/{id}/stats")
    ResponseEntity<NicheStatsResponse> stats(@PathVariable("id") UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(NicheStatsService.CACHE_TTL).cachePrivate())
                .body(stats.stats(id));
    }
}
