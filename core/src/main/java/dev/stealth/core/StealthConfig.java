package dev.stealth.core;

import java.util.Set;

/**
 * Per-repo settings from {@code .stealth.yml}. A placeholder until the config ticket adds the rest
 * of ADR-0004; {@link #defaults()} is what a repo without the file gets.
 *
 * @param disabledAnalyzers ids of analyzers that don't run
 */
public record StealthConfig(Set<String> disabledAnalyzers) {

    public StealthConfig {
        disabledAnalyzers = Set.copyOf(disabledAnalyzers);
    }

    public static StealthConfig defaults() {
        return new StealthConfig(Set.of());
    }

    public boolean isEnabled(String analyzerId) {
        return !disabledAnalyzers.contains(analyzerId);
    }
}
