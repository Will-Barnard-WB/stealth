package dev.stealth.cli;

import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Findings grouped by the change that addresses them. Every analyzer points a finding at the line
 * to change (a transitive vulnerability points at the dependency that brings it in), so findings at
 * the same line are one fix: upgrading {@code spring-boot-starter-parent} deals with its own
 * outdatedness and the vulnerabilities in everything it manages.
 */
final class FixList {

    private FixList() {}

    /** One change and the findings it covers. */
    record Fix(Location location, List<Finding> findings) {

        Severity worst() {
            return findings.stream()
                    .map(Finding::severity)
                    .min(Comparator.naturalOrder())
                    .orElse(Severity.INFO);
        }

        /** ADR-0002's severity weights, summed: how much this fix is worth. */
        int weight() {
            return findings.stream().mapToInt(f -> FixList.weight(f.severity())).sum();
        }

        /** The dependency-freshness finding at this line, if the dependency is outdated. */
        Optional<Finding> outdated() {
            return findings.stream().filter(f -> f.ruleId().startsWith("deps/")).findFirst();
        }

        List<Finding> vulnerabilities() {
            return findings.stream().filter(f -> f.advisory().isPresent()).toList();
        }
    }

    /** Most important first: worst severity, then total weight, then location. */
    static List<Fix> of(List<Finding> findings) {
        // Dependency findings at the same line share one fix (one version to change). Anything
        // else, such as a missing CODEOWNERS file or a secret, is a fix of its own even when it
        // shares a location: adding CI doesn't add CODEOWNERS.
        Map<List<Object>, List<Finding>> groups = new LinkedHashMap<>();
        for (Finding finding : findings) {
            List<Object> key =
                    finding.component().isPresent()
                            ? List.of(finding.location())
                            : List.of(finding.location(), finding.fingerprint());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(finding);
        }
        return groups.values().stream()
                .map(group -> new Fix(group.getFirst().location(), sorted(group)))
                .sorted(
                        Comparator.comparing(Fix::worst)
                                .thenComparing(Comparator.comparingInt(Fix::weight).reversed())
                                .thenComparing(fix -> fix.location().path().orElse(""))
                                .thenComparingInt(fix -> fix.location().line().orElse(0)))
                .toList();
    }

    private static List<Finding> sorted(List<Finding> findings) {
        return findings.stream()
                .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::ruleId))
                .toList();
    }

    static int weight(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 15;
            case HIGH -> 8;
            case MEDIUM -> 3;
            case LOW -> 1;
            case INFO -> 0;
        };
    }
}
