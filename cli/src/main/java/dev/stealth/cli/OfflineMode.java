package dev.stealth.cli;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Whether this run may use the network, set by {@code --offline}. Beans are created before picocli
 * parses the arguments, so they read this when they need it rather than at startup.
 */
@Component
public class OfflineMode {

    private final AtomicBoolean offline = new AtomicBoolean();

    public boolean isOffline() {
        return offline.get();
    }

    public void set(boolean offline) {
        this.offline.set(offline);
    }
}
