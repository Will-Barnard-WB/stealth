package dev.stealth.core.hygiene;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.git.WorkingTreeFiles;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Reports how the repository is run rather than what's in the code: no CODEOWNERS, no CI, modules
 * without tests, large committed files and branches nobody has touched in months.
 *
 * <p>Branch checks only run when the analyzed directory is the root of its git work tree: for a
 * subdirectory (one service in a monorepo, or a fixture inside stealth's own repo) the branches
 * belong to the whole repository, not to it.
 */
public class RepoHygieneAnalyzer implements Analyzer {

    static final Rule MISSING_CODEOWNERS =
            rule(
                    "hygiene/missing-codeowners",
                    "Missing CODEOWNERS",
                    "No CODEOWNERS file, so nobody is asked to review changes automatically.");
    static final Rule MISSING_CI =
            rule(
                    "hygiene/missing-ci",
                    "Missing CI",
                    "No CI configuration, so changes aren't built and tested automatically.");
    static final Rule MISSING_TESTS =
            rule(
                    "hygiene/missing-tests",
                    "Missing tests",
                    "A module has code in src/main/java but no tests in src/test/java.");
    static final Rule STALE_BRANCHES =
            rule(
                    "hygiene/stale-branches",
                    "Stale branches",
                    "Branches with no commits for months clutter the repository and hide abandoned"
                            + " work.");
    static final Rule LARGE_FILE =
            rule(
                    "hygiene/large-file",
                    "Large file",
                    "A large file is committed, which bloats every clone.");

    public static final Period DEFAULT_STALE_BRANCH_AFTER = Period.ofDays(90);
    public static final long DEFAULT_LARGE_FILE_BYTES = 5L * 1024 * 1024;

    static final List<String> CODEOWNERS_PATHS =
            List.of("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS");

    static final List<String> CI_FILES =
            List.of(
                    ".gitlab-ci.yml",
                    "Jenkinsfile",
                    "azure-pipelines.yml",
                    ".circleci/config.yml",
                    "bitbucket-pipelines.yml");

    private static final String GITHUB_WORKFLOWS = ".github/workflows";

    private static final int BRANCHES_LISTED = 5;

    private final MavenModelLoader loader;
    private final Period staleBranchAfter;
    private final long largeFileBytes;
    private final Clock clock;

    public RepoHygieneAnalyzer(
            MavenModelLoader loader, Period staleBranchAfter, long largeFileBytes, Clock clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.staleBranchAfter = Objects.requireNonNull(staleBranchAfter, "staleBranchAfter");
        this.largeFileBytes = largeFileBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "hygiene";
    }

    @Override
    public Category category() {
        return Category.TECH;
    }

    @Override
    public List<Rule> rules() {
        return List.of(MISSING_CODEOWNERS, MISSING_CI, MISSING_TESTS, STALE_BRANCHES, LARGE_FILE);
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        Path root = context.root();
        List<Finding> findings = new ArrayList<>();
        if (CODEOWNERS_PATHS.stream().noneMatch(p -> Files.isRegularFile(root.resolve(p)))) {
            findings.add(
                    repositoryFinding(
                            MISSING_CODEOWNERS,
                            "No CODEOWNERS file: nobody is asked to review changes automatically",
                            "Add .github/CODEOWNERS naming who owns each part of the code"));
        }
        if (!hasCi(root)) {
            findings.add(
                    repositoryFinding(
                            MISSING_CI,
                            "No CI configuration: changes aren't built or tested automatically",
                            "Add a CI workflow, e.g. .github/workflows/ci.yml running ./mvnw"
                                    + " verify"));
        }
        findings.addAll(missingTests(context));
        StealthConfig.Thresholds thresholds = context.config().thresholds();
        findings.addAll(
                largeFiles(context, thresholds.hygieneLargeFileBytes().orElse(largeFileBytes)));
        staleBranches(root, thresholds.hygieneStaleBranchAfter().orElse(staleBranchAfter))
                .ifPresent(findings::add);
        return findings;
    }

    private static boolean hasCi(Path root) throws IOException {
        if (CI_FILES.stream().anyMatch(p -> Files.isRegularFile(root.resolve(p)))) {
            return true;
        }
        Path workflows = root.resolve(GITHUB_WORKFLOWS);
        if (!Files.isDirectory(workflows)) {
            return false;
        }
        try (Stream<Path> files = Files.list(workflows)) {
            return files.anyMatch(
                    f -> {
                        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        return Files.isRegularFile(f)
                                && (name.endsWith(".yml") || name.endsWith(".yaml"));
                    });
        }
    }

    /**
     * Modules with Java sources and no tests. Without a Maven build, the repository root is the one
     * module.
     */
    private List<Finding> missingTests(RepoContext context) {
        MavenProjectModel model = context.get(loader);
        List<Finding> findings = new ArrayList<>();
        List<ModuleDirectory> modules = new ArrayList<>();
        if (model.isEmpty()) {
            modules.add(new ModuleDirectory("", Optional.empty()));
        } else {
            for (MavenModule module : model.modules()) {
                modules.add(new ModuleDirectory(module.directory(), Optional.of(module.pomPath())));
            }
        }
        for (ModuleDirectory module : modules) {
            Path directory = context.root().resolve(module.directory());
            if (hasJavaFiles(directory.resolve("src/main/java"))
                    && !hasJavaFiles(directory.resolve("src/test/java"))) {
                String name =
                        module.directory().isEmpty()
                                ? "The project"
                                : "Module " + module.directory();
                Location location =
                        new Location(
                                module.pomPath(),
                                OptionalInt.empty(),
                                Optional.of(module.directory()));
                findings.add(
                        finding(
                                MISSING_TESTS,
                                name + " has code in src/main/java but no tests in src/test/java",
                                location,
                                "Add tests under src/test/java, starting with the code that changes"
                                        + " most",
                                module.directory()));
            }
        }
        return findings;
    }

    private static boolean hasJavaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.anyMatch(f -> f.toString().endsWith(".java") && Files.isRegularFile(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Files git tracks (or would track) over the size threshold. */
    private List<Finding> largeFiles(RepoContext context, long largeFileBytes) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (String path : context.get(WorkingTreeFiles.FILES)) {
            long size = Files.size(context.root().resolve(path));
            if (size > largeFileBytes) {
                findings.add(
                        finding(
                                LARGE_FILE,
                                "Large file committed (" + megabytes(size) + "): " + path,
                                new Location(
                                        Optional.of(path), OptionalInt.empty(), Optional.empty()),
                                "Move it to Git LFS or an artifact store, and remove it from"
                                        + " history",
                                path));
            }
        }
        return findings;
    }

    /**
     * One finding listing the local and remote-tracking branches whose newest commit is older than
     * the threshold, other than the checked-out and default branches.
     */
    private Optional<Finding> staleBranches(Path root, Period staleBranchAfter) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            return Optional.empty();
        }
        try (Repository repository = builder.setMustExist(true).build()) {
            if (repository.isBare()
                    || !repository.getWorkTree().toPath().toRealPath().equals(root.toRealPath())) {
                return Optional.empty();
            }
            Set<String> excluded = new HashSet<>(Set.of("main", "master"));
            Optional.ofNullable(repository.getBranch()).ifPresent(excluded::add);
            Ref originHead = repository.exactRef(Constants.R_REMOTES + "origin/HEAD");
            if (originHead != null && originHead.isSymbolic()) {
                excluded.add(shortName(originHead.getTarget().getName()));
            }

            Instant cutoff =
                    clock.instant().atZone(ZoneOffset.UTC).minus(staleBranchAfter).toInstant();
            // Newest commit per branch name, so a branch and its origin/ copy count once
            Map<String, Instant> newest = new TreeMap<>();
            try (RevWalk walk = new RevWalk(repository)) {
                List<Ref> refs = new ArrayList<>();
                refs.addAll(repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS));
                refs.addAll(repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES));
                for (Ref ref : refs) {
                    if (ref.isSymbolic() || ref.getObjectId() == null) {
                        continue;
                    }
                    String name = shortName(ref.getName());
                    if (excluded.contains(name)) {
                        continue;
                    }
                    RevCommit commit = walk.parseCommit(ref.getObjectId());
                    Instant when = commit.getCommitterIdent().getWhenAsInstant();
                    newest.merge(name, when, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            List<String> stale =
                    newest.entrySet().stream()
                            .filter(e -> e.getValue().isBefore(cutoff))
                            .map(Map.Entry::getKey)
                            .toList();
            if (stale.isEmpty()) {
                return Optional.empty();
            }
            String listed =
                    stale.stream().limit(BRANCHES_LISTED).collect(Collectors.joining(", "))
                            + (stale.size() > BRANCHES_LISTED
                                    ? ", +" + (stale.size() - BRANCHES_LISTED) + " more"
                                    : "");
            return Optional.of(
                    repositoryFinding(
                            STALE_BRANCHES,
                            stale.size()
                                    + (stale.size() == 1 ? " branch" : " branches")
                                    + " with no commits in the last "
                                    + describe(staleBranchAfter)
                                    + ": "
                                    + listed,
                            "Merge or delete them; tag anything worth keeping"));
        }
    }

    /**
     * {@code feature/x} for {@code refs/heads/feature/x} and {@code refs/remotes/origin/feature/x}.
     */
    private static String shortName(String refName) {
        if (refName.startsWith(Constants.R_HEADS)) {
            return refName.substring(Constants.R_HEADS.length());
        }
        String remote = refName.substring(Constants.R_REMOTES.length());
        int slash = remote.indexOf('/');
        return slash < 0 ? remote : remote.substring(slash + 1);
    }

    /** {@code P90D} as "90 days", {@code P6M} as "6 months". */
    static String describe(Period period) {
        if (period.getYears() > 0 && period.getMonths() == 0 && period.getDays() == 0) {
            return plural(period.getYears(), "year");
        }
        if (period.getDays() == 0) {
            return plural((int) period.toTotalMonths(), "month");
        }
        return plural(period.getDays() + (int) period.toTotalMonths() * 30, "day");
    }

    private static String plural(int count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static Finding repositoryFinding(Rule rule, String message, String advice) {
        return finding(rule, message, Location.repository(), advice, "");
    }

    private static Finding finding(
            Rule rule, String message, Location location, String advice, String identity) {
        return new Finding(
                rule.id(),
                rule.category(),
                rule.defaultSeverity(),
                message,
                location,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Remediation(Optional.empty(), Optional.of(advice))),
                Fingerprints.of(rule.id(), identity));
    }

    private record ModuleDirectory(String directory, Optional<String> pomPath) {}

    private static Rule rule(String id, String name, String description) {
        return new Rule(
                id,
                name,
                description,
                URI.create(
                        "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/hygiene.md#"
                                + id.replace('/', '-')),
                Category.TECH,
                Severity.LOW);
    }
}
