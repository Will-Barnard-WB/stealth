package dev.stealth.cli;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** {@code ~/.stealth}: the MCP token and logs. The {@code stealth.home} property overrides it. */
@Component
public class StealthHome {

    public Path path() {
        String override = System.getProperty("stealth.home");
        return override != null
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".stealth");
    }
}
