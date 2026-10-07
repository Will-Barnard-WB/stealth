package dev.stealth.mcp;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.ConfigException;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfigLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Doctor runs, kept per repository while its files don't change, so an agent asking {@code
 * repo_health} then {@code list_findings} pays for one scan. A repository's state is its HEAD
 * commit plus every uncommitted or untracked file's size and modification time; outside git nothing
 * is cached. Entries also expire after {@link #TIME_TO_LIVE}, for new releases and advisories.
 */
@Component
class RepoScans {

    static final Duration TIME_TO_LIVE = Duration.ofHours(1);
    private static final int MAX_REPOSITORIES = 20;
    private static final Logger LOG = LoggerFactory.getLogger(RepoScans.class);

    /**
     * One doctor run.
     *
     * @param cached whether this came from an earlier run rather than a new scan
     */
    record Scan(DoctorReport report, Instant scannedAt, boolean cached) {}

    private record Cached(String state, Scan scan) {}

    private final AnalyzerRunner runner;
    private final Clock clock;
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    // Least recently used first
    private final Map<Path, Cached> entries =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
                    return size() > MAX_REPOSITORIES;
                }
            };

    RepoScans(AnalyzerRunner runner, Clock clock) {
        this.runner = runner;
        this.clock = clock;
    }

    /**
     * @param root an existing directory, already normalized
     * @param refresh scan again even if nothing changed
     * @throws ConfigException if the repository's {@code .stealth.yml} is invalid
     */
    Scan scan(Path root, boolean refresh) throws ConfigException, InterruptedException {
        synchronized (locks.computeIfAbsent(root, r -> new Object())) {
            Optional<String> state = state(root);
            if (!refresh && state.isPresent()) {
                Cached entry;
                synchronized (entries) {
                    entry = entries.get(root);
                }
                if (entry != null
                        && entry.state().equals(state.get())
                        && entry.scan().scannedAt().plus(TIME_TO_LIVE).isAfter(clock.instant())) {
                    return new Scan(entry.scan().report(), entry.scan().scannedAt(), true);
                }
            }

            Set<String> rules = new HashSet<>();
            Set<String> analyzers = new HashSet<>();
            for (Analyzer analyzer : runner.analyzers()) {
                analyzers.add(analyzer.id());
                analyzer.rules().forEach(rule -> rules.add(rule.id()));
            }
            StealthConfigLoader.Loaded loaded = StealthConfigLoader.load(root, rules, analyzers);
            Instant started = clock.instant();
            DoctorReport report =
                    runner.run(new RepoContext(root, loaded.config()))
                            .withWarnings(loaded.warnings());
            LOG.info(
                    "Scanned {} in {} ms",
                    root,
                    Duration.between(started, clock.instant()).toMillis());
            Scan scan = new Scan(report, started, false);
            state.ifPresent(
                    s -> {
                        synchronized (entries) {
                            entries.put(root, new Cached(s, scan));
                        }
                    });
            return scan;
        }
    }

    /** The repository with its {@code .stealth.yml}, for tools that run more than doctor. */
    RepoContext context(Path root) throws ConfigException {
        Set<String> rules = new HashSet<>();
        Set<String> analyzers = new HashSet<>();
        for (Analyzer analyzer : runner.analyzers()) {
            analyzers.add(analyzer.id());
            analyzer.rules().forEach(rule -> rules.add(rule.id()));
        }
        return new RepoContext(root, StealthConfigLoader.load(root, rules, analyzers).config());
    }

    /** The repository's state, or empty outside git (or if git can't be read). */
    static Optional<String> state(Path root) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            return Optional.empty();
        }
        try (Repository repository = builder.setMustExist(true).build();
                Git git = new Git(repository)) {
            ObjectId head = repository.resolve("HEAD");
            Status status = git.status().call();
            Set<String> changed = new TreeSet<>(status.getUncommittedChanges());
            changed.addAll(status.getUntracked());
            File workTree = repository.getWorkTree();
            StringBuilder state = new StringBuilder(head == null ? "no-head" : head.name());
            for (String path : changed) {
                Path file = workTree.toPath().resolve(path);
                state.append('\n').append(path);
                if (Files.isRegularFile(file)) {
                    state.append(' ')
                            .append(Files.size(file))
                            .append(' ')
                            .append(Files.getLastModifiedTime(file).toMillis());
                }
            }
            return Optional.of(state.toString());
        } catch (IOException | org.eclipse.jgit.api.errors.GitAPIException e) {
            return Optional.empty();
        }
    }
}
