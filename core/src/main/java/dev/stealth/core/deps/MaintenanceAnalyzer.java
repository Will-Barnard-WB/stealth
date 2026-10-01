package dev.stealth.core.deps;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import dev.stealth.core.maven.ResolvedDependency;
import dev.stealth.core.maven.VersionSource;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reports direct dependencies whose newest release is older than a threshold (2 years by default):
 * they're current, so freshness can't see them, but nobody is shipping fixes for them. Versions
 * managed by an external parent or BOM (such as Spring Boot's) are skipped: whoever maintains the
 * BOM keeps them current.
 */
public class MaintenanceAnalyzer implements Analyzer {

    static final Rule NO_RECENT_RELEASE =
            new Rule(
                    "maintenance/no-recent-release",
                    "No recent release",
                    "The dependency hasn't had a release in a long time and may be unmaintained.",
                    URI.create(
                            "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/maintenance.md"),
                    Category.TECH,
                    Severity.LOW);

    public static final Period DEFAULT_STALE_AFTER = Period.ofYears(2);

    /**
     * Finished rather than abandoned: tiny API or annotation artifacts that never need another
     * release. The {@code .stealth.yml} allowlist will extend this.
     */
    static final Set<String> KNOWN_STABLE =
            Set.of(
                    "javax.inject:javax.inject",
                    "aopalliance:aopalliance",
                    "com.google.code.findbugs:jsr305");

    private static final int CONCURRENT_LOOKUPS = 8;

    private final MavenModelLoader loader;
    private final MavenCentralClient central;
    private final MavenCentralSearch search;
    private final Period staleAfter;
    private final Clock clock;

    public MaintenanceAnalyzer(
            MavenModelLoader loader,
            MavenCentralClient central,
            MavenCentralSearch search,
            Period staleAfter,
            Clock clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.central = Objects.requireNonNull(central, "central");
        this.search = Objects.requireNonNull(search, "search");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "maintenance";
    }

    @Override
    public Category category() {
        return Category.TECH;
    }

    @Override
    public List<Rule> rules() {
        return List.of(NO_RECENT_RELEASE);
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        MavenProjectModel model = context.get(loader);
        Map<List<Object>, Candidate> candidates = new LinkedHashMap<>();
        for (MavenModule module : model.modules()) {
            for (ResolvedDependency dependency : module.dependencies()) {
                if (dependency.internal()
                        || dependency.versionSource() == VersionSource.PARENT
                        || dependency.versionSource() == VersionSource.BOM
                        || KNOWN_STABLE.contains(dependency.key())) {
                    continue;
                }
                Candidate candidate = new Candidate(dependency, location(dependency, module));
                candidates.putIfAbsent(List.of(dependency.key(), candidate.location()), candidate);
            }
        }

        Map<String, Optional<Release>> latest = lookUp(candidates.values());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate cutoff =
                today.minus(
                        context.config().thresholds().maintenanceStaleAfter().orElse(staleAfter));
        List<Finding> findings = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            latest.get(candidate.dependency().key())
                    .filter(release -> release.date().isBefore(cutoff))
                    .ifPresent(release -> findings.add(finding(candidate, release, today)));
        }
        return findings;
    }

    /**
     * The newest stable release of each artifact and when it was published. Empty when the artifact
     * isn't on Maven Central or its newest release isn't in the search index yet, which only
     * happens for recent releases.
     */
    private Map<String, Optional<Release>> lookUp(Iterable<Candidate> candidates)
            throws IOException, InterruptedException {
        Map<String, Future<Optional<Release>>> lookups = new LinkedHashMap<>();
        try (ExecutorService executor =
                Executors.newFixedThreadPool(
                        CONCURRENT_LOOKUPS,
                        Thread.ofVirtual().name("maintenance-lookup-", 0).factory())) {
            for (Candidate candidate : candidates) {
                ResolvedDependency dependency = candidate.dependency();
                lookups.computeIfAbsent(
                        dependency.key(), key -> executor.submit(() -> newestRelease(dependency)));
            }
            Map<String, Optional<Release>> releases = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Optional<Release>>> lookup : lookups.entrySet()) {
                try {
                    releases.put(lookup.getKey(), lookup.getValue().get());
                } catch (ExecutionException e) {
                    // A partial answer would read as "everything else is maintained"
                    throw new IOException(
                            "couldn't check " + lookup.getKey() + ": " + e.getCause().getMessage(),
                            e.getCause());
                }
            }
            return releases;
        }
    }

    private Optional<Release> newestRelease(ResolvedDependency dependency)
            throws IOException, InterruptedException {
        Optional<List<String>> versions =
                central.versions(dependency.groupId(), dependency.artifactId());
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        String newest =
                Versions.newerVersion(versions.get(), dependency.version())
                        .orElse(dependency.version());
        return search.published(dependency.groupId(), dependency.artifactId(), newest)
                .map(
                        published ->
                                new Release(
                                        newest, LocalDate.ofInstant(published, ZoneOffset.UTC)));
    }

    private Finding finding(Candidate candidate, Release release, LocalDate today) {
        ResolvedDependency dependency = candidate.dependency();
        int years = Period.between(release.date(), today).getYears();
        String age = years >= 1 ? years + (years == 1 ? " year" : " years") : "a while";
        String message =
                dependency.key()
                        + ": no release in "
                        + age
                        + " (newest is "
                        + release.version()
                        + ", released "
                        + release.date()
                        + ")"
                        + ("test".equals(dependency.scope()) ? " [test scope]" : "");
        return new Finding(
                NO_RECENT_RELEASE.id(),
                Category.TECH,
                NO_RECENT_RELEASE.defaultSeverity(),
                message,
                candidate.location(),
                Optional.of(
                        "pkg:maven/"
                                + dependency.groupId()
                                + "/"
                                + dependency.artifactId()
                                + "@"
                                + dependency.version()),
                Optional.empty(),
                Optional.of(
                        new Remediation(
                                Optional.empty(),
                                Optional.of("Look for a maintained replacement"))),
                Fingerprints.of(
                        NO_RECENT_RELEASE.id(),
                        candidate.location().module().orElse(""),
                        dependency.key()));
    }

    private static Location location(ResolvedDependency dependency, MavenModule module) {
        return dependency
                .declaredAt()
                .orElseGet(
                        () ->
                                new Location(
                                        Optional.of(module.pomPath()),
                                        OptionalInt.empty(),
                                        Optional.of(module.directory())));
    }

    private record Candidate(ResolvedDependency dependency, Location location) {}

    private record Release(String version, LocalDate date) {}
}
