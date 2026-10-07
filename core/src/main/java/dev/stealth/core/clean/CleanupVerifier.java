package dev.stealth.core.clean;

import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.RepoContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a change: runs doctor on the working tree and on a base commit (checked out in a temporary
 * worktree) and compares findings by fingerprint. This is how an agent proves its own edits worked,
 * and how a cleanup branch is checked against {@code main}.
 */
public class CleanupVerifier {

    private final AnalyzerRunner runner;

    public CleanupVerifier(AnalyzerRunner runner) {
        this.runner = runner;
    }

    /**
     * @param base the commit to compare against; empty means {@code HEAD}, which needs uncommitted
     *     changes to compare
     * @param tests run the working tree's tests too, when present
     */
    public Verification verify(
            RepoContext context, Optional<String> base, Optional<TestRunner> tests)
            throws CleanException, InterruptedException {
        GitWorkspace git = GitWorkspace.of(context.root());
        if (base.isEmpty() && !git.hasChanges()) {
            throw new CleanException(
                    "nothing to verify: the working tree matches HEAD. Pass the commit or branch to"
                            + " compare with, e.g. main");
        }
        String ref = base.orElse("HEAD");
        Path relative;
        try {
            relative = git.top.toRealPath().relativize(context.root().toRealPath());
        } catch (IOException e) {
            throw new CleanException("can't resolve " + context.root() + ": " + e.getMessage(), e);
        }
        Path worktree = git.addDetachedWorktree(ref);
        DoctorReport before;
        try {
            // The same .stealth.yml for both, so only the code differs
            before =
                    runner.run(
                            new RepoContext(
                                    worktree.resolve(relative.toString()), context.config()));
        } finally {
            git.removeWorktree(worktree);
        }
        DoctorReport after = runner.run(context);
        Optional<TestRunner.Run> run =
                tests.isPresent() ? Optional.of(tests.get().run(context.root())) : Optional.empty();
        return compare(ref, before, after, run);
    }

    static Verification compare(
            String base, DoctorReport before, DoctorReport after, Optional<TestRunner.Run> tests) {
        Set<String> was = fingerprints(before.findings());
        Set<String> now = fingerprints(after.findings());
        return new Verification(
                base,
                before.score(),
                after.score(),
                before.findings().stream().filter(f -> !now.contains(f.fingerprint())).toList(),
                after.findings().stream().filter(f -> !was.contains(f.fingerprint())).toList(),
                before.findings().size(),
                after.findings().size(),
                tests);
    }

    private static Set<String> fingerprints(List<Finding> findings) {
        return findings.stream().map(Finding::fingerprint).collect(Collectors.toSet());
    }
}
