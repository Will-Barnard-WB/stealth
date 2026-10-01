package dev.stealth.core;

import dev.stealth.core.score.FixPlanner;
import dev.stealth.core.score.HealthScore;
import dev.stealth.core.score.ScoringEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a {@code stealth doctor} run.
 *
 * @param findings findings from every analyzer that finished, in analyzer order
 * @param results one per analyzer, in analyzer order, including skipped and unselected ones
 * @param warnings things the user should know about the run, such as {@code .stealth.yml} problems
 *     or expired allowlist entries
 */
public record DoctorReport(
        List<Finding> findings, List<AnalyzerResult> results, List<String> warnings) {

    public DoctorReport {
        findings = List.copyOf(findings);
        results = List.copyOf(results);
        warnings = List.copyOf(warnings);
    }

    public DoctorReport(List<Finding> findings, List<AnalyzerResult> results) {
        this(findings, results, List.of());
    }

    /** The same report with {@code more} warnings first, e.g. from loading the config. */
    public DoctorReport withWarnings(List<String> more) {
        List<String> all = new ArrayList<>(more);
        all.addAll(warnings);
        return new DoctorReport(findings, results, all);
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
