package dev.stealth.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A single problem reported by an {@link Analyzer}. See ADR-0001.
 *
 * @param ruleId stable {@code <analyzer>/<rule>} id
 * @param severity the effective severity, after {@code .stealth.yml} overrides
 * @param message one line, human-readable; may change between versions
 * @param location where to look first
 * @param relatedLocations other places the same problem is, such as the other copies of a
 *     duplicated block (SARIF {@code relatedLocations}); usually empty
 * @param component Package URL of the dependency involved, if any
 * @param fingerprint stable identity from {@link Fingerprints#of}
 */
public record Finding(
        String ruleId,
        Category category,
        Severity severity,
        String message,
        Location location,
        List<Location> relatedLocations,
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
        relatedLocations = List.copyOf(relatedLocations);
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(advisory, "advisory");
        Objects.requireNonNull(remediation, "remediation");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /** A finding at one location. */
    public Finding(
            String ruleId,
            Category category,
            Severity severity,
            String message,
            Location location,
            Optional<String> component,
            Optional<Advisory> advisory,
            Optional<Remediation> remediation,
            String fingerprint) {
        this(
                ruleId,
                category,
                severity,
                message,
                location,
                List.of(),
                component,
                advisory,
                remediation,
                fingerprint);
    }
}
