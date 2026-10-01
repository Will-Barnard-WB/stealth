package dev.stealth.core;

import dev.stealth.core.score.FixPlanner;
import dev.stealth.core.score.HealthScore;
import dev.stealth.core.score.ScoringEngine;
import java.util.List;

/**
 * The outcome of a {@code stealth doctor} run.
 *
 * @param findings findings from every analyzer that finished, in analyzer order
 * @param results one per selected analyzer, in analyzer order, including skipped ones
 */
public record DoctorReport(List<Finding> findings, List<AnalyzerResult> results) {

    public DoctorReport {
        findings = List.copyOf(findings);
        results = List.copyOf(results);
    }

    /** The health score (ADR-0002) for these findings. */
    public HealthScore score() {
        return ScoringEngine.score(findings, results);
    }

    /** The fixes to make, most important first (ADR-0002). */
    public List<FixPlanner.Fix> fixes() {
        return FixPlanner.plan(findings);
    }
}
