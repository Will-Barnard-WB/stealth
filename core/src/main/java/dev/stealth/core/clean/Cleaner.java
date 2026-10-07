package dev.stealth.core.clean;

import dev.stealth.core.RepoContext;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Applies a {@link CleanupPlan}'s proven patches on a new branch, built in a temporary git worktree
 * so the user's checkout, branch and uncommitted work are never touched. The repository's own tests
 * are the gate: they run before and after, and a patch that makes them worse is dropped. One commit
 * per patch, so each is easy to review or revert.
 */
public class Cleaner {

    private static final DateTimeFormatter BRANCH_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final PatchPlanner planner;
    private final Clock clock;

    public Cleaner(PatchPlanner planner, Clock clock) {
        this.planner = planner;
        this.clock = clock;
    }

    /**
     * @param includeMajor also apply proven patches that move a dependency to a new major
     * @param tests how to run the tests; empty skips them (the result then says so)
     * @param allowFailingTests go ahead when the tests already fail, requiring no new failures
     * @param branch the branch to create; empty names it {@code stealth/clean-<date>-<time>}
     */
    public record Options(
            boolean includeMajor,
            Optional<TestRunner> tests,
            boolean allowFailingTests,
            Optional<String> branch) {}

    public CleanupResult apply(RepoContext context, CleanupPlan plan, Options options)
            throws CleanException, InterruptedException {
        List<Patch> chosen = chosen(plan, options.includeMajor());
        GitWorkspace git = GitWorkspace.of(context.root());
        git.requireClean();
        String base = git.head();
        if (chosen.isEmpty()) {
            return new CleanupResult(
                    Optional.empty(),
                    base,
                    List.of(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    plan.vulnerabilities(),
                    plan.vulnerabilities());
        }

        String branch = options.branch().orElse(defaultBranch(git));
        if (git.branchExists(branch)) {
            throw new CleanException(
                    "branch " + branch + " already exists; pick another with --branch");
        }
        Path relative = relative(git.top, context.root());
        Path worktree = git.addWorktree(branch);
        List<CleanupResult.Applied> applied = new ArrayList<>();
        List<CleanupResult.Failed> failed = new ArrayList<>();
        try {
            Path project = worktree.resolve(relative.toString());
            Optional<TestRunner.Run> baseline = Optional.empty();
            if (options.tests().isPresent()) {
                TestRunner.Run run = options.tests().get().run(project);
                if (!run.passed() && !options.allowFailingTests()) {
                    throw new CleanException(
                            "the tests fail before any change ("
                                    + run.command()
                                    + "), so they can't show whether a patch is safe. Fix them,"
                                    + " or pass --allow-failing-tests to require no new failures."
                                    + "\n"
                                    + run.output());
                }
                baseline = Optional.of(run);
            }

            for (Patch patch : chosen) {
                applied.add(commit(git, worktree, project, relative, patch));
            }
            Optional<TestRunner.Run> after = Optional.empty();
            if (baseline.isPresent()) {
                TestRunner tests = options.tests().get();
                TestRunner.Run run = tests.run(project);
                after = Optional.of(run);
                if (!run.noWorseThan(baseline.get())) {
                    // Something broke the build: try the patches one at a time to find out what
                    git.resetTo(worktree, base);
                    applied.clear();
                    after = Optional.empty();
                    for (Patch patch : chosen) {
                        CleanupResult.Applied attempt =
                                commit(git, worktree, project, relative, patch);
                        TestRunner.Run single = tests.run(project);
                        if (single.noWorseThan(baseline.get())) {
                            applied.add(attempt);
                            after = Optional.of(single);
                        } else {
                            git.dropLastCommit(worktree);
                            failed.add(
                                    new CleanupResult.Failed(patch, why(single, baseline.get())));
                        }
                    }
                }
            }

            int remaining = plan.vulnerabilities();
            if (!applied.isEmpty()) {
                try {
                    remaining = planner.vulnerabilities(project, context.config()).size();
                } catch (IOException e) {
                    // Keep the plan's count; the patches were proven before anyway
                }
            }
            return new CleanupResult(
                    applied.isEmpty() ? Optional.empty() : Optional.of(branch),
                    base,
                    applied,
                    failed,
                    baseline,
                    after,
                    plan.vulnerabilities(),
                    remaining);
        } finally {
            git.removeWorktree(worktree);
            if (applied.isEmpty()) {
                git.deleteBranch(branch);
            }
        }
    }

    /**
     * The proven patches to apply, in an order whose line numbers stay valid. With {@code
     * includeMajor}, a proven major patch replaces the same-major patch editing the same thing.
     */
    static List<Patch> chosen(CleanupPlan plan, boolean includeMajor) {
        Map<String, Patch> byEdit = new LinkedHashMap<>();
        for (Patch patch : plan.patches()) {
            boolean wanted = patch.safe() || (includeMajor && patch.proof().accepted());
            if (!wanted) {
                continue;
            }
            byEdit.merge(
                    patch.edit().key(),
                    patch,
                    (a, b) ->
                            new ComparableVersion(a.edit().value())
                                                    .compareTo(
                                                            new ComparableVersion(b.edit().value()))
                                            >= 0
                                    ? a
                                    : b);
        }
        List<PomEdit> order = PomEditor.ordered(byEdit.values().stream().map(Patch::edit).toList());
        return byEdit.values().stream()
                .sorted(Comparator.comparingInt(p -> order.indexOf(p.edit())))
                .toList();
    }

    private CleanupResult.Applied commit(
            GitWorkspace git, Path worktree, Path project, Path relative, Patch patch)
            throws CleanException, InterruptedException {
        try {
            PomEditor.apply(project, patch.edit());
        } catch (IOException e) {
            throw new CleanException(
                    "couldn't apply " + patch.edit().describe() + ": " + e.getMessage(), e);
        }
        String pom = relative.resolve(patch.edit().pomPath()).toString().replace('\\', '/');
        String commit = git.commit(worktree, List.of(pom), message(patch));
        return new CleanupResult.Applied(patch, commit);
    }

    static String message(Patch patch) {
        StringBuilder message =
                new StringBuilder("stealth clean: ")
                        .append(patch.edit().describe().replace(" in pom.xml", ""))
                        .append("\n\n");
        message.append("Clears ")
                .append(patch.proof().cleared().size())
                .append(
                        patch.proof().cleared().size() == 1
                                ? " known vulnerability"
                                : " known vulnerabilities")
                .append(patch.bom().map(b -> " by moving " + b).orElse(""))
                .append(":\n");
        patch.proof().cleared().stream()
                .limit(15)
                .forEach(v -> message.append("- ").append(v).append('\n'));
        if (patch.proof().cleared().size() > 15) {
            message.append("- and ").append(patch.proof().cleared().size() - 15).append(" more\n");
        }
        message.append("\nProven on the re-resolved dependency tree before committing: no new")
                .append(" known vulnerabilities.");
        if (patch.crossesMajor()) {
            message.append(" Moves to a new major version: check behaviour, not just the build.");
        }
        return message.toString();
    }

    private static String why(TestRunner.Run run, TestRunner.Run baseline) {
        if (run.timedOut()) {
            return "the tests timed out";
        }
        var fresh = run.newFailures(baseline);
        if (!fresh.isEmpty()) {
            return "new test failures: "
                    + fresh.stream().limit(5).collect(Collectors.joining(", "));
        }
        // The first error line usually says what broke; keep the output for the details
        String first =
                run.output()
                        .lines()
                        .filter(l -> l.contains("ERROR") || l.contains("FAIL"))
                        .findFirst()
                        .or(
                                () ->
                                        run.output()
                                                .lines()
                                                .filter(l -> !l.isBlank())
                                                .reduce((a, b) -> b))
                        .orElse("no output")
                        .strip();
        return "the build failed: " + first + "\n" + run.output();
    }

    private String defaultBranch(GitWorkspace git) throws CleanException, InterruptedException {
        String base = "stealth/clean-" + LocalDateTime.now(clock).format(BRANCH_TIME);
        String branch = base;
        for (int n = 2; git.branchExists(branch); n++) {
            branch = base + "-" + n;
        }
        return branch;
    }

    /** Where the analysed directory sits inside the repository (empty at the top). */
    private static Path relative(Path top, Path root) throws CleanException {
        try {
            return top.toRealPath().relativize(root.toRealPath());
        } catch (IOException e) {
            throw new CleanException("can't resolve " + root + ": " + e.getMessage(), e);
        }
    }
}
