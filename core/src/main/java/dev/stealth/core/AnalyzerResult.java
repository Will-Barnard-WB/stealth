package dev.stealth.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * How one analyzer's run went. Any status other than {@link AnalyzerStatus#OK} means findings for
 * its category are incomplete.
 *
 * @param error why it failed or timed out
 */
public record AnalyzerResult(
        String analyzerId,
        Category category,
        AnalyzerStatus status,
        Duration duration,
        Optional<String> error) {

    public AnalyzerResult {
        Objects.requireNonNull(analyzerId, "analyzerId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(error, "error");
    }
}
