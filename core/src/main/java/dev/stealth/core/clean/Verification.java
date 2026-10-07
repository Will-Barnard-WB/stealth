package dev.stealth.core.clean;

import dev.stealth.core.Finding;
import dev.stealth.core.score.HealthScore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How the repository now compares with a base commit: what got fixed, what got worse.
 *
 * @param base the commit compared against, as given (e.g. {@code HEAD}, {@code main})
 * @param resolved findings the base had and the working tree doesn't
 * @param introduced findings the working tree has and the base didn't
 * @param tests the working tree's test run, when asked for
 */
public record Verification(
        String base,
        HealthScore scoreBefore,
        HealthScore scoreAfter,
        List<Finding> resolved,
        List<Finding> introduced,
        int findingsBefore,
        int findingsAfter,
        Optional<TestRunner.Run> tests) {

    public Verification {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(scoreBefore, "scoreBefore");
        Objects.requireNonNull(scoreAfter, "scoreAfter");
        resolved = List.copyOf(resolved);
        introduced = List.copyOf(introduced);
        Objects.requireNonNull(tests, "tests");
    }

    /** Nothing new found, and the tests pass if they ran. */
    public boolean green() {
        return introduced.isEmpty() && tests.map(TestRunner.Run::passed).orElse(true);
    }
}
