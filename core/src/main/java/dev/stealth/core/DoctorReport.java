package dev.stealth.core;

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
}
