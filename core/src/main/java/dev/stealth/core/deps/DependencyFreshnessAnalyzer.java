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
import dev.stealth.core.maven.PomReference;
import dev.stealth.core.maven.ResolvedDependency;
import dev.stealth.core.maven.VersionSource;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reports dependencies with a newer stable release. Versions managed by an external parent or an
 * imported BOM are reported once, on the parent or BOM, since that's the one version to change.
 */
public class DependencyFreshnessAnalyzer implements Analyzer {

    private static final String HELP =
            "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/deps.md";

    static final Rule OUTDATED_MAJOR =
            rule("deps/outdated-major", "Major version behind", Severity.MEDIUM);
    static final Rule OUTDATED_MINOR =
            rule("deps/outdated-minor", "Minor version behind", Severity.LOW);
    static final Rule OUTDATED_PATCH =
            rule("deps/outdated-patch", "Patch version behind", Severity.INFO);

    private static final int CONCURRENT_LOOKUPS = 8;

    private final MavenModelLoader loader;
    private final MavenCentralClient central;

    public DependencyFreshnessAnalyzer(MavenModelLoader loader, MavenCentralClient central) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.central = Objects.requireNonNull(central, "central");
    }

    @Override
    public String id() {
        return "deps";
    }

    @Override
    public Category category() {
        return Category.TECH;
    }

    @Override
    public List<Rule> rules() {
        return List.of(OUTDATED_MAJOR, OUTDATED_MINOR, OUTDATED_PATCH);
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        MavenProjectModel model = context.get(loader);
        Map<Subject.Key, Subject> subjects = subjects(model);
        Map<String, Optional<List<String>>> versions = lookUp(subjects.values());

        List<Finding> findings = new ArrayList<>();
        for (Subject subject : subjects.values()) {
            versions.get(subject.coordinates())
                    .flatMap(available -> Versions.newerVersion(available, subject.version()))
                    .ifPresent(latest -> findings.add(finding(subject, latest)));
        }
        return findings;
    }

    /**
     * What to check, once per place a version is set: a parent-managed version shared by five
     * modules is one subject.
     */
    private static Map<Subject.Key, Subject> subjects(MavenProjectModel model) {
        Map<Subject.Key, Subject> subjects = new LinkedHashMap<>();
        for (MavenModule module : model.modules()) {
            int parentManaged = count(module, VersionSource.PARENT);
            module.parent().ifPresent(p -> add(subjects, Subject.of(p, module, parentManaged)));
            for (PomReference bom : module.importedBoms()) {
                add(subjects, Subject.of(bom, module, count(module, VersionSource.BOM)));
            }
            for (ResolvedDependency dependency : module.dependencies()) {
                if (dependency.internal()
                        || dependency.versionSource() == VersionSource.PARENT
                        || dependency.versionSource() == VersionSource.BOM) {
                    continue;
                }
                add(subjects, Subject.of(dependency, module));
            }
        }
        return subjects;
    }

    private static void add(Map<Subject.Key, Subject> subjects, Subject subject) {
        subjects.merge(subject.key(), subject, Subject::plusManaged);
    }

    private static int count(MavenModule module, VersionSource source) {
        return (int)
                module.dependencies().stream().filter(d -> d.versionSource() == source).count();
    }

    /** Looks up every artifact once, a few at a time so Maven Central isn't hammered. */
    private Map<String, Optional<List<String>>> lookUp(Iterable<Subject> subjects)
            throws IOException, InterruptedException {
        Map<String, Future<Optional<List<String>>>> lookups = new LinkedHashMap<>();
        try (ExecutorService executor =
                Executors.newFixedThreadPool(
                        CONCURRENT_LOOKUPS, Thread.ofVirtual().name("deps-lookup-", 0).factory())) {
            for (Subject subject : subjects) {
                lookups.computeIfAbsent(
                        subject.coordinates(),
                        key ->
                                executor.submit(
                                        () ->
                                                central.versions(
                                                        subject.groupId(), subject.artifactId())));
            }

            Map<String, Optional<List<String>>> versions = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, Future<Optional<List<String>>>> lookup : lookups.entrySet()) {
                try {
                    versions.put(lookup.getKey(), lookup.getValue().get());
                } catch (ExecutionException e) {
                    failures.add(lookup.getKey() + ": " + e.getCause().getMessage());
                }
            }
            // Reporting only some of the outdated dependencies would read as "the rest are up to
            // date", so a partial lookup fails the analyzer instead
            if (!failures.isEmpty()) {
                throw new IOException(
                        "couldn't check "
                                + failures.size()
                                + " of "
                                + lookups.size()
                                + " artifacts on Maven Central: "
                                + failures.getFirst());
            }
            return versions;
        }
    }

    private static Finding finding(Subject subject, String latest) {
        Rule rule =
                switch (Versions.update(subject.version(), latest)) {
                    case MAJOR -> OUTDATED_MAJOR;
                    case MINOR -> OUTDATED_MINOR;
                    case PATCH -> OUTDATED_PATCH;
                };
        String message =
                subject.coordinates()
                        + " "
                        + subject.version()
                        + " is outdated: "
                        + latest
                        + " is available"
                        + (subject.managed() > 0
                                ? " (manages "
                                        + subject.managed()
                                        + " of the module's dependencies)"
                                : "");
        return new Finding(
                rule.id(),
                rule.category(),
                rule.defaultSeverity(),
                message,
                subject.location(),
                Optional.of(
                        "pkg:maven/"
                                + subject.groupId()
                                + "/"
                                + subject.artifactId()
                                + "@"
                                + subject.version()),
                Optional.empty(),
                Optional.of(
                        new Remediation(Optional.of(latest), Optional.of("Upgrade to " + latest))),
                Fingerprints.of(rule.id(), subject.module(), subject.coordinates()));
    }

    private static Rule rule(String id, String name, Severity severity) {
        return new Rule(
                id,
                name,
                "A newer " + name.split(" ")[0].toLowerCase() + " release is available.",
                URI.create(HELP + "#" + id.replace('/', '-')),
                Category.TECH,
                severity);
    }

    /**
     * One version to check.
     *
     * @param module the module directory the fingerprint is keyed on: where the version is set
     * @param managed how many dependencies this parent or BOM manages, 0 for a plain dependency
     */
    private record Subject(
            String groupId,
            String artifactId,
            String version,
            Location location,
            String module,
            int managed) {

        record Key(String coordinates, String version, Location location) {}

        static Subject of(PomReference reference, MavenModule module, int managed) {
            return create(
                    reference.groupId(),
                    reference.artifactId(),
                    reference.version(),
                    reference.declaredAt(),
                    module,
                    managed);
        }

        static Subject of(ResolvedDependency dependency, MavenModule module) {
            return create(
                    dependency.groupId(),
                    dependency.artifactId(),
                    dependency.version(),
                    dependency.declaredAt(),
                    module,
                    0);
        }

        private static Subject create(
                String groupId,
                String artifactId,
                String version,
                Optional<Location> declaredAt,
                MavenModule module,
                int managed) {
            Location location =
                    declaredAt.orElseGet(
                            () ->
                                    new Location(
                                            Optional.of(module.pomPath()),
                                            OptionalInt.empty(),
                                            Optional.of(module.directory())));
            return new Subject(
                    groupId,
                    artifactId,
                    version,
                    location,
                    location.module().orElse(module.directory()),
                    managed);
        }

        String coordinates() {
            return groupId + ":" + artifactId;
        }

        Key key() {
            return new Key(coordinates(), version, location);
        }

        Subject plusManaged(Subject other) {
            return new Subject(
                    groupId,
                    artifactId,
                    version,
                    location,
                    module,
                    Math.max(managed, other.managed));
        }
    }
}
