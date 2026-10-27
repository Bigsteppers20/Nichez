package app.nichez.common;

import app.nichez.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /config}: public, because the app asks for its minimum version before anyone has
 * signed in. Everything here is meant to be public.
 */
@RestController
@RequestMapping("/api/v1")
class AppConfigController {

    record Features(boolean live, boolean reels, boolean directMessages) {
    }

    record Links(String terms, String privacy) {
    }

    record AppConfig(String minAppVersion, Features features, Links links) {
    }

    private final AppProperties app;

    AppConfigController(AppProperties app) {
        this.app = app;
    }

    @GetMapping("/config")
    AppConfig config() {
        return new AppConfig(app.minAppVersion(),
                new Features(app.featureLive(), app.featureReels(), app.featureDirectMessages()),
                new Links(app.termsUrl(), app.privacyUrl()));
    }
}
