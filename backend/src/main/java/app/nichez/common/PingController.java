package app.nichez.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness check for clients and uptime monitors. Reveals only the build version. */
@RestController
@RequestMapping("/api/v1")
class PingController {

    record PingResponse(String status, String version) {
    }

    private final String version;

    PingController(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "unknown";
    }

    @GetMapping("/ping")
    PingResponse ping() {
        return new PingResponse("ok", version);
    }
}
