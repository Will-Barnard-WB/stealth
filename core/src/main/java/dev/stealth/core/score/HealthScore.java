package dev.stealth.core.score;

import dev.stealth.core.Category;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A repository's health per ADR-0002: a 0–100 score per category and overall.
 *
 * @param overall {@code 0.6 × security + 0.4 × tech}; empty when a category didn't run (with {@code
 *     doctor --secrets}, say), since the formula needs both
 * @param overallCapped whether a critical finding capped the overall score at 50
 */
public record HealthScore(
        CategoryScore security,
        CategoryScore tech,
        OptionalInt overall,
        boolean overallCapped,
        int scoringVersion) {

    public HealthScore {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(tech, "tech");
        Objects.requireNonNull(overall, "overall");
    }

    public CategoryScore category(Category category) {
        return category == Category.SECURITY ? security : tech;
    }

    /**
     * Display only (ADR-0002): A ≥ 90, B ≥ 75, C ≥ 60, D ≥ 40, F below. CI gates use the number.
     */
    public static String grade(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        return score >= 40 ? "D" : "F";
    }

    /** How complete a category's score is. */
    public enum Status {
        /** Every analyzer in the category finished. */
        COMPLETE,
        /** An analyzer in the category failed or timed out, so findings may be missing. */
        INCOMPLETE,
        /**
         * Only some of the category's analyzers were selected (e.g. {@code doctor --hygiene}), so
         * the score covers just those.
         */
        PARTIAL,
        /** No analyzer in the category ran. */
        NOT_RUN
    }

    /**
     * One category's score.
     *
     * @param value the unrounded score, which the overall score is computed from
     * @param capped whether a critical finding capped it at 50
     * @param deductions what each analyzer took off, largest first
     * @param analyzers the category's analyzers that ran, in registration order
     */
    public record CategoryScore(
            Category category,
            Status status,
            double value,
            int score,
            boolean capped,
            List<Deduction> deductions,
            List<String> analyzers) {

        public CategoryScore {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(status, "status");
            deductions = List.copyOf(deductions);
            analyzers = List.copyOf(analyzers);
        }
    }

    /**
     * What one analyzer's findings took off a category's score.
     *
     * @param points after diminishing returns and the per-analyzer cap
     * @param capped whether the per-analyzer cap applied
     */
    public record Deduction(String analyzerId, int findings, double points, boolean capped) {}
}
