package dev.stealth.core.score;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Severity;
import dev.stealth.core.score.HealthScore.CategoryScore;
import dev.stealth.core.score.HealthScore.Deduction;
import dev.stealth.core.score.HealthScore.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * ADR-0002's scoring formula, as a pure function of the findings and analyzer results. Change
 * nothing here without bumping {@link #SCORING_VERSION} and updating the ADR: CI gates depend on
 * the numbers.
 */
public final class ScoringEngine {

    public static final int SCORING_VERSION = 1;

    static final double ANALYZER_CAP = 40;
    static final double CRITICAL_CEILING = 50;
    static final double SECURITY_WEIGHT = 0.6;
    static final double TECH_WEIGHT = 0.4;

    private ScoringEngine() {}

    public static int weight(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 15;
            case HIGH -> 8;
            case MEDIUM -> 3;
            case LOW -> 1;
            case INFO -> 0;
        };
    }

    public static HealthScore score(List<Finding> findings, List<AnalyzerResult> results) {
        CategoryScore security = category(Category.SECURITY, findings, results);
        CategoryScore tech = category(Category.TECH, findings, results);
        boolean anyCritical = findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
        double overall = overall(security.value(), tech.value(), anyCritical);
        boolean bothRan = isScored(security) && isScored(tech);
        return new HealthScore(
                security,
                tech,
                bothRan ? OptionalInt.of(round(overall)) : OptionalInt.empty(),
                anyCritical
                        && SECURITY_WEIGHT * security.value() + TECH_WEIGHT * tech.value()
                                > CRITICAL_CEILING,
                SCORING_VERSION);
    }

    /**
     * The overall score for {@code findings} without the critical ceiling, unrounded: what {@link
     * FixPlanner} compares to rank fixes. With the ceiling, every fix but the last critical one
     * would gain nothing while a critical finding remains, and the ranking would fall back to
     * tie-breakers.
     */
    static double rankingPoints(List<Finding> findings) {
        return SECURITY_WEIGHT * 100
                - SECURITY_WEIGHT * Math.min(100, deducted(Category.SECURITY, findings))
                + TECH_WEIGHT * 100
                - TECH_WEIGHT * Math.min(100, deducted(Category.TECH, findings));
    }

    private static double deducted(Category category, List<Finding> findings) {
        return category(category, findings, List.of()).deductions().stream()
                .mapToDouble(Deduction::points)
                .sum();
    }

    private static double overall(double security, double tech, boolean anyCritical) {
        double overall = SECURITY_WEIGHT * security + TECH_WEIGHT * tech;
        return anyCritical ? Math.min(overall, CRITICAL_CEILING) : overall;
    }

    private static CategoryScore category(
            Category category, List<Finding> findings, List<AnalyzerResult> results) {
        // Findings are scored by the analyzer that reported them: the prefix of the rule id
        Map<String, List<Finding>> byAnalyzer = new TreeMap<>();
        for (Finding finding : findings) {
            if (finding.category() == category) {
                byAnalyzer
                        .computeIfAbsent(analyzerId(finding), id -> new ArrayList<>())
                        .add(finding);
            }
        }
        List<Deduction> deductions = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, List<Finding>> analyzer : byAnalyzer.entrySet()) {
            double points = diminishing(analyzer.getValue());
            Deduction deduction =
                    new Deduction(
                            analyzer.getKey(),
                            analyzer.getValue().size(),
                            Math.min(points, ANALYZER_CAP),
                            points > ANALYZER_CAP);
            deductions.add(deduction);
            total += deduction.points();
        }
        deductions.sort(
                Comparator.comparingDouble(Deduction::points)
                        .reversed()
                        .thenComparing(Deduction::analyzerId));

        double value = Math.max(0, 100 - total);
        boolean critical =
                findings.stream()
                        .anyMatch(
                                f -> f.category() == category && f.severity() == Severity.CRITICAL);
        boolean capped = critical && value > CRITICAL_CEILING;
        if (critical) {
            value = Math.min(value, CRITICAL_CEILING);
        }
        return new CategoryScore(
                category,
                status(category, results),
                value,
                round(value),
                capped,
                deductions,
                ran(category, results));
    }

    /** The i-th heaviest finding (1-based) deducts {@code weight / √i}. */
    private static double diminishing(List<Finding> findings) {
        List<Integer> weights =
                findings.stream()
                        .map(f -> weight(f.severity()))
                        .sorted(Comparator.reverseOrder())
                        .toList();
        double total = 0;
        for (int i = 0; i < weights.size(); i++) {
            total += weights.get(i) / Math.sqrt(i + 1);
        }
        return total;
    }

    /** Whether the category ran in full, so it can count towards the overall score. */
    private static boolean isScored(CategoryScore score) {
        return score.status() == Status.COMPLETE || score.status() == Status.INCOMPLETE;
    }

    private static Status status(Category category, List<AnalyzerResult> results) {
        List<AnalyzerResult> inCategory =
                results.stream().filter(r -> r.category() == category).toList();
        if (ran(category, results).isEmpty()) {
            return Status.NOT_RUN;
        }
        if (inCategory.stream()
                .anyMatch(
                        r ->
                                r.status() == AnalyzerStatus.FAILED
                                        || r.status() == AnalyzerStatus.TIMED_OUT)) {
            return Status.INCOMPLETE;
        }
        return inCategory.stream().anyMatch(r -> r.status() == AnalyzerStatus.NOT_SELECTED)
                ? Status.PARTIAL
                : Status.COMPLETE;
    }

    /** The category's analyzers that ran, finished or not. */
    private static List<String> ran(Category category, List<AnalyzerResult> results) {
        return results.stream()
                .filter(r -> r.category() == category)
                .filter(
                        r ->
                                r.status() != AnalyzerStatus.SKIPPED
                                        && r.status() != AnalyzerStatus.NOT_SELECTED)
                .map(AnalyzerResult::analyzerId)
                .toList();
    }

    static String analyzerId(Finding finding) {
        return finding.ruleId().substring(0, finding.ruleId().indexOf('/'));
    }

    /** Half-up, as ADR-0002 says; scores are never negative. */
    private static int round(double value) {
        return (int) Math.floor(value + 0.5);
    }
}
