package dev.stealth.core;

import java.util.Objects;
import java.util.Optional;

/**
 * A single problem reported by an {@link Analyzer}. See ADR-0001.
 *
 * @param ruleId stable {@code <analyzer>/<rule>} id
 * @param severity the effective severity, after {@code .stealth.yml} overrides
 * @param message one line, human-readable; may change between versions
 * @param component Package URL of the dependency involved, if any
 * @param fingerprint stable identity from {@link Fingerprints#of}
 */
public record Finding(
        String ruleId,
        Category category,
        Severity severity,
        String message,
        Location location,
        Optional<String> component,
        Optional<Advisory> advisory,
        Optional<Remediation> remediation,
        String fingerprint) {

    public Finding {
        Rule.requireValidId(ruleId);
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(advisory, "advisory");
        Objects.requireNonNull(remediation, "remediation");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
