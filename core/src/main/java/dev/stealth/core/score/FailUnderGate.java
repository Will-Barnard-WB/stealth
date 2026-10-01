package dev.stealth.core.score;

import dev.stealth.core.Category;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.score.HealthScore.CategoryScore;
import dev.stealth.core.score.HealthScore.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The CI gate: compares the scores with {@code fail-under}. A threshold on a score that wasn't
 * computed in full (a filtered run, or a category that didn't run) can't be judged, and is reported
 * as an error rather than passed, so a misconfigured CI job doesn't go green by accident.
 */
public final class FailUnderGate {

    private FailUnderGate() {}

    /**
     * What the gate decided.
     *
     * @param failures one line per score below its threshold
     * @param error why the gate couldn't be applied, if it couldn't
     */
    public record Result(List<String> failures, Optional<String> error) {

        public Result {
            failures = List.copyOf(failures);
        }

        public boolean passed() {
            return failures.isEmpty() && error.isEmpty();
        }

        /** {@code 0} passed, {@code 1} a score is below its threshold, {@code 2} couldn't judge. */
        public int exitCode() {
            if (error.isPresent()) {
                return 2;
            }
            return failures.isEmpty() ? 0 : 1;
        }
    }

    public static Result check(HealthScore score, StealthConfig.FailUnder failUnder) {
        List<String> failures = new ArrayList<>();
        if (failUnder.overall().isPresent()) {
            if (score.overall().isEmpty()) {
                return error("the overall score needs a run of every analyzer; some were left out");
            }
            below("overall", score.overall().getAsInt(), failUnder.overall(), failures);
        }
        for (Category category : List.of(Category.SECURITY, Category.TECH)) {
            OptionalInt threshold =
                    category == Category.SECURITY ? failUnder.security() : failUnder.tech();
            if (threshold.isEmpty()) {
                continue;
            }
            CategoryScore categoryScore = score.category(category);
            String name = category == Category.SECURITY ? "security" : "tech";
            if (categoryScore.status() == Status.NOT_RUN
                    || categoryScore.status() == Status.PARTIAL) {
                return error("the " + name + " score needs every " + name + " analyzer to run");
            }
            below(name, categoryScore.score(), threshold, failures);
        }
        return new Result(failures, Optional.empty());
    }

    private static void below(
            String name, int score, OptionalInt threshold, List<String> failures) {
        if (score < threshold.getAsInt()) {
            failures.add(name + " score " + score + " is below " + threshold.getAsInt());
        }
    }

    private static Result error(String message) {
        return new Result(List.of(), Optional.of(message));
    }
}
