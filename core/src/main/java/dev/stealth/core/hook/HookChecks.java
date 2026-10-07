package dev.stealth.core.hook;

import dev.stealth.core.Finding;
import dev.stealth.core.Remediation;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.clean.Command;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * What the Claude Code hooks check: only what an edit, or the session's uncommitted changes,
 * introduced compared with HEAD. A dependency added or changed in a POM must not be vulnerable or
 * invented; a file must not gain a hardcoded secret. Existing debt is never reported here (that's
 * {@code doctor}'s job), so the hooks stay quiet unless the agent made something worse.
 */
public final class HookChecks {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);

    /** {@link DependencyCheck#check}, as a function so tests can stand in for the network. */
    @FunctionalInterface
    public interface DependencyChecker {
        DependencyCheck.Result check(String groupId, String artifactId, Optional<String> version)
                throws IOException, InterruptedException;
    }

    private final SecretsAnalyzer secrets;
    private final DependencyChecker dependencies;
    private final Clock clock;

    public HookChecks(SecretsAnalyzer secrets, DependencyChecker dependencies, Clock clock) {
        this.secrets = secrets;
        this.dependencies = dependencies;
        this.clock = clock;
    }

    /**
     * Problems one edited file introduced, one message each, written for the agent.
     *
     * @param top the repository's top-level directory
     * @param file the edited file (absolute)
     */
    public List<String> afterEdit(Path top, Path file, StealthConfig config)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        // Real paths: the hook's cwd and git can disagree through symlinks (/tmp on macOS)
        Path absolute = file.toRealPath();
        Path root = top.toRealPath();
        if (!absolute.startsWith(root)) {
            return List.of();
        }
        String path = root.relativize(absolute).toString().replace('\\', '/');
        if (config.isIgnored(path)) {
            return List.of();
        }
        String now = Files.readString(absolute, StandardCharsets.UTF_8);
        Optional<String> before = committed(root, path);
        List<String> problems = new ArrayList<>();
        if (path.equals("pom.xml") || path.endsWith("/pom.xml")) {
            problems.addAll(dependencyProblems(path, before.orElse(""), now));
        }
        problems.addAll(secretProblems(path, before.orElse(""), now, config));
        return problems;
    }

    /** Problems across every file changed since HEAD (committed or not, new files included). */
    public List<String> beforeStop(Path top, StealthConfig config)
            throws IOException, InterruptedException {
        List<String> problems = new ArrayList<>();
        for (String path : changedFiles(top)) {
            problems.addAll(afterEdit(top, top.resolve(path), config));
        }
        return problems;
    }

    /** The top-level directory of the git repository containing {@code directory}, if any. */
    public static Optional<Path> repositoryTop(Path directory)
            throws IOException, InterruptedException {
        Command.Result result =
                Command.run(
                        directory,
                        GIT_TIMEOUT,
                        List.of("git", "rev-parse", "--show-toplevel"),
                        false);
        return result.ok()
                ? Optional.of(Path.of(result.output().strip()).toAbsolutePath().normalize())
                : Optional.empty();
    }

    private List<String> dependencyProblems(String path, String before, String now)
            throws IOException, InterruptedException {
        Map<String, String> was = PomDependencies.of(before);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> dependency : PomDependencies.of(now).entrySet()) {
            String version = dependency.getValue();
            if (version.equals(was.get(dependency.getKey()))) {
                continue;
            }
            String[] coordinates = dependency.getKey().split(":", 2);
            DependencyCheck.Result result =
                    dependencies.check(coordinates[0], coordinates[1], Optional.of(version));
            describe(path, dependency.getKey(), version, result).ifPresent(problems::add);
        }
        return problems;
    }

    /** The message for a dependency that shouldn't go in, if it shouldn't. */
    static Optional<String> describe(
            String path, String coordinates, String version, DependencyCheck.Result result) {
        if (!result.found()) {
            return Optional.of(
                    path
                            + ": "
                            + coordinates
                            + " isn't on Maven Central. Check the groupId and artifactId (an"
                            + " invented or misspelled name can be registered by an attacker).");
        }
        if (result.vulnerabilities().isEmpty()) {
            return Optional.empty();
        }
        List<Finding> found = result.vulnerabilities();
        String worst =
                found.stream()
                        .map(Finding::severity)
                        .min(Comparator.naturalOrder())
                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                        .orElse("?");
        String ids =
                found.stream()
                        .limit(3)
                        .map(
                                f ->
                                        f.advisory()
                                                .map(
                                                        a ->
                                                                a.aliases().stream()
                                                                        .filter(
                                                                                x ->
                                                                                        x
                                                                                                .startsWith(
                                                                                                        "CVE-"))
                                                                        .findFirst()
                                                                        .orElse(a.id()))
                                                .orElse("?"))
                        .collect(Collectors.joining(", "));
        Optional<String> fixedIn =
                found.stream()
                        .map(Finding::remediation)
                        .flatMap(Optional::stream)
                        .map(Remediation::fixedVersion)
                        .flatMap(Optional::stream)
                        .max(Comparator.comparing(ComparableVersion::new));
        StringBuilder message =
                new StringBuilder(path)
                        .append(": ")
                        .append(coordinates)
                        .append(' ')
                        .append(version)
                        .append(" has ")
                        .append(found.size())
                        .append(
                                found.size() == 1
                                        ? " known vulnerability"
                                        : " known vulnerabilities")
                        .append(" (worst ")
                        .append(worst)
                        .append(": ")
                        .append(ids)
                        .append(found.size() > 3 ? ", …" : "")
                        .append(").");
        Optional<String> latest = result.latestVersion().filter(v -> !v.equals(version));
        if (latest.isPresent() && result.latestVulnerabilities().map(n -> n == 0).orElse(false)) {
            message.append(" Use ").append(latest.get()).append(", the latest, which has none.");
        } else if (fixedIn.isPresent()) {
            message.append(" Use ").append(fixedIn.get()).append(" or later.");
        }
        if (result.maintained().map(m -> !m).orElse(false)) {
            message.append(" It also looks unmaintained (no release since ")
                    .append(result.latestReleased().map(Object::toString).orElse("?"))
                    .append("); consider an alternative.");
        }
        return Optional.of(message.toString());
    }

    private List<String> secretProblems(
            String path, String before, String now, StealthConfig config) {
        LocalDate today = LocalDate.now(clock);
        Set<String> existing =
                secrets.scanText(path, before).stream()
                        .map(Finding::fingerprint)
                        .collect(Collectors.toSet());
        List<Finding> added =
                secrets.scanText(path, now).stream()
                        .filter(f -> !existing.contains(f.fingerprint()))
                        .toList();
        // The repository's .stealth.yml: allowlists, ignored paths, severity overrides
        return config.apply(added, today).findings().stream()
                .map(
                        f ->
                                path
                                        + ":"
                                        + f.location().line().orElse(1)
                                        + ": "
                                        + f.message()
                                        + " Read it from an environment variable or a secret store"
                                        + " instead, and don't commit it.")
                .toList();
    }

    /** The file as committed at HEAD, if it's tracked. */
    private static Optional<String> committed(Path top, String path)
            throws IOException, InterruptedException {
        Command.Result result =
                Command.run(top, GIT_TIMEOUT, List.of("git", "show", "HEAD:" + path), false);
        return result.ok() ? Optional.of(result.output()) : Optional.empty();
    }

    /** Files changed since HEAD: modified, added, untracked (not deleted, not ignored). */
    private static List<String> changedFiles(Path top) throws IOException, InterruptedException {
        Command.Result status =
                Command.run(
                        top,
                        GIT_TIMEOUT,
                        List.of("git", "status", "--porcelain", "--untracked-files=all"),
                        false);
        if (!status.ok()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (String line : status.output().lines().toList()) {
            if (line.length() < 4 || line.charAt(0) == 'D' || line.charAt(1) == 'D') {
                continue;
            }
            String path = line.substring(3);
            int rename = path.indexOf(" -> ");
            files.add(rename >= 0 ? path.substring(rename + 4) : path.replace("\"", ""));
        }
        return files;
    }
}
