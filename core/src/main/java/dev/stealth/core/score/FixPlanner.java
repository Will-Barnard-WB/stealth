package dev.stealth.core.score;

import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The "fix these first" list (ADR-0002): findings grouped by the one change that resolves them,
 * ordered by how much each change matters.
 *
 * <p>Every analyzer points a finding at the line to change: a transitive vulnerability points at
 * the dependency that brings it in, a Boot-managed one at the parent. So dependency findings at the
 * same line are one fix: upgrading {@code spring-boot-starter-parent} deals with its own
 * outdatedness and every vulnerability that comes in through it. Anything else, such as a missing
 * CODEOWNERS file or a secret, is a fix of its own even when it shares a location.
 */
public final class FixPlanner {

    private FixPlanner() {}

    /**
     * One change and the findings it resolves.
     *
     * @param points how much the overall score rises if every finding here goes away, before the
     *     critical ceiling (see ADR-0002): used for ranking
     */
    public record Fix(Location location, List<Finding> findings, double points) {

        public Fix {
            Objects.requireNonNull(location, "location");
            findings = List.copyOf(findings);
        }

        public Severity worst() {
            return findings.stream()
                    .map(Finding::severity)
                    .min(Comparator.naturalOrder())
                    .orElse(Severity.INFO);
        }

        /** Only these findings, keeping the fix's points; empty if none are left. */
        public Optional<Fix> only(Set<Severity> severities) {
            List<Finding> kept =
                    findings.stream().filter(f -> severities.contains(f.severity())).toList();
            return kept.isEmpty() ? Optional.empty() : Optional.of(new Fix(location, kept, points));
        }
    }

    /**
     * Ordered as ADR-0002 says: highest severity, then points gained, then fixes with a concrete
     * version before those without, security before tech, then rule id, path and fingerprint so
     * ties always break the same way.
     */
    public static List<Fix> plan(List<Finding> findings) {
        Map<List<Object>, List<Finding>> groups = new LinkedHashMap<>();
        for (Finding finding : findings) {
            List<Object> key =
                    finding.component().isPresent()
                            ? List.of(finding.location())
                            : List.of(finding.location(), finding.fingerprint());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(finding);
        }

        double everything = ScoringEngine.rankingPoints(findings);
        List<Fix> fixes = new ArrayList<>();
        for (List<Finding> group : groups.values()) {
            Set<Finding> fixed = Set.copyOf(group);
            List<Finding> remaining = findings.stream().filter(f -> !fixed.contains(f)).toList();
            double points = ScoringEngine.rankingPoints(remaining) - everything;
            fixes.add(new Fix(group.getFirst().location(), sorted(group), points));
        }
        fixes.sort(ORDER);
        return List.copyOf(fixes);
    }

    private static final Comparator<Fix> ORDER =
            Comparator.comparing(Fix::worst)
                    .thenComparing(Comparator.comparingDouble(Fix::points).reversed())
                    .thenComparing(fix -> hasFixedVersion(fix) ? 0 : 1)
                    .thenComparing(
                            fix ->
                                    fix.findings().getFirst().category() == Category.SECURITY
                                            ? 0
                                            : 1)
                    .thenComparing(fix -> fix.findings().getFirst().ruleId())
                    .thenComparing(fix -> fix.location().path().orElse(""))
                    .thenComparingInt(fix -> fix.location().line().orElse(0))
                    .thenComparing(fix -> fix.findings().getFirst().fingerprint());

    private static boolean hasSecurityFinding(Fix fix) {
        return fix.findings().stream().anyMatch(f -> f.category() == Category.SECURITY);
    }

    private static boolean hasFixedVersion(Fix fix) {
        return fix.findings().stream()
                .anyMatch(f -> f.remediation().flatMap(Remediation::fixedVersion).isPresent());
    }

    /** Within a fix: most severe first, then by rule and fingerprint. */
    private static List<Finding> sorted(List<Finding> findings) {
        return findings.stream()
                .sorted(
                        Comparator.comparing(Finding::severity)
                                .thenComparing(Finding::ruleId)
                                .thenComparing(Finding::fingerprint))
                .collect(Collectors.toList());
    }
}
