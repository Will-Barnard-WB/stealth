package dev.stealth.core.clean;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code apply} did.
 *
 * @param branch the new branch with the patches, if any were applied
 * @param base the commit the branch starts from
 * @param applied the patches committed to the branch, each with its commit
 * @param failed patches dropped because they broke the build, with why
 * @param baseline the tests before any patch, when tests ran
 * @param after the tests with every applied patch, when tests ran
 * @param vulnerabilitiesBefore known vulnerabilities before, as {@code g:a ADVISORY}
 * @param vulnerabilitiesAfter known vulnerabilities on the branch
 */
public record CleanupResult(
        Optional<String> branch,
        String base,
        List<Applied> applied,
        List<Failed> failed,
        Optional<TestRunner.Run> baseline,
        Optional<TestRunner.Run> after,
        int vulnerabilitiesBefore,
        int vulnerabilitiesAfter) {

    public CleanupResult {
        Objects.requireNonNull(branch, "branch");
        applied = List.copyOf(applied);
        failed = List.copyOf(failed);
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(after, "after");
    }

    public record Applied(Patch patch, String commit) {}

    /**
     * @param reason e.g. the tests that started failing
     */
    public record Failed(Patch patch, String reason) {}
}
