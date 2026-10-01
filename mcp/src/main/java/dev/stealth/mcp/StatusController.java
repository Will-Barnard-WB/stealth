package dev.stealth.mcp;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /status}: lets {@code stealth mcp status} tell a stealth server from another one. */
@RestController
class StatusController {

    private final String version;

    StatusController(@Value("${spring.ai.mcp.server.version}") String version) {
        this.version = version;
    }

    @GetMapping(LocalOnlyFilter.STATUS_PATH)
    Map<String, String> status() {
        return Map.of("name", "stealth", "version", version);
    }
}
