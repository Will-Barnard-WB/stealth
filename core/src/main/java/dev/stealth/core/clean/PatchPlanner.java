package dev.stealth.core.clean;

import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.deps.Versions;
import dev.stealth.core.maven.ManagedVersion;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import dev.stealth.core.maven.PomReference;
import dev.stealth.core.maven.ResolvedDependency;
import dev.stealth.core.maven.VersionSource;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Works out POM edits that clear known vulnerabilities without a framework major upgrade, and
 * proves each one by re-resolving the dependency tree and checking it again before anything is
 * written to the repository.
 *
 * <p>For each vulnerable dependency, the edit is the cheapest that changes its version: the line in
 * this repository that sets it; else the property its inherited parent manages it with (Spring
 * Boot's {@code tomcat.version}, for instance); else a pin in {@code <dependencyManagement>}. Edits
 * of the same line, property or pin are merged. The version is the lowest that fixes every advisory
 * fixable within the current major; advisories only fixed in a new major become a separate patch
 * marked {@link Patch#crossesMajor()}, which is never applied by default.
 */
public class PatchPlanner {

    private static final int CONCURRENT_PROOFS = 4;

    static final String RULE = "vuln/known-vulnerability";

    /** Known vulnerabilities of the dependency tree under a root, as {@code g:a ADVISORY-ID}. */
    @FunctionalInterface
    public interface TreeCheck {
        Set<String> vulnerabilities(RepoContext context) throws Exception;
    }

    private final MavenModelLoader loader;
    private final TreeCheck check;

    public PatchPlanner(MavenModelLoader loader, VulnerabilityAnalyzer vulnerabilities) {
        this(
                loader,
                context ->
                        vulnerabilities.analyze(context).stream()
                                .map(PatchPlanner::vulnerabilityKey)
                                .flatMap(Optional::stream)
                                .collect(Collectors.toSet()));
    }

    PatchPlanner(MavenModelLoader loader, TreeCheck check) {
        this.loader = loader;
        this.check = check;
    }

    public CleanupPlan plan(RepoContext context, DoctorReport report)
            throws IOException, InterruptedException {
        List<Finding> vulnerabilities =
                report.findings().stream().filter(f -> f.ruleId().equals(RULE)).toList();
        List<Finding> secrets =
                report.findings().stream().filter(f -> f.ruleId().startsWith("secrets/")).toList();
        if (vulnerabilities.isEmpty()) {
            return new CleanupPlan(List.of(), Optional.empty(), List.of(), secrets, 0);
        }
        Set<String> baseline = new TreeSet<>();
        vulnerabilities.forEach(f -> vulnerabilityKey(f).ifPresent(baseline::add));

        MavenProjectModel model = context.get(loader);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        // Only fixes that exist: OSV can name a commercial-only release (Spring 5.3.4x, say)
        Map<String, Boolean> published = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.function.BiPredicate<String, String> isPublished =
                (key, version) ->
                        published.computeIfAbsent(
                                key + ":" + version,
                                k -> {
                                    String[] ga = key.split(":", 2);
                                    return loader.isPublished(ga[0], ga[1], version);
                                });
        for (Target target : targets(vulnerabilities, model)) {
            for (Candidate candidate : target.candidates(isPublished)) {
                candidates.merge(
                        // Separate tiers of the same edit stay separate patches
                        candidate.edit().key() + "+" + candidate.level(),
                        candidate,
                        Candidate::merge);
            }
        }

        List<Patch> patches = new ArrayList<>();
        // Each proof re-resolves the whole tree, so run a few at once
        try (ExecutorService executor =
                Executors.newFixedThreadPool(
                        CONCURRENT_PROOFS, Thread.ofVirtual().name("clean-proof-", 0).factory())) {
            List<Future<List<Patch>>> proving = new ArrayList<>();
            for (Candidate candidate : candidates.values()) {
                proving.add(executor.submit(() -> proveCandidate(context, candidate, baseline)));
            }
            for (Future<List<Patch>> proved : proving) {
                try {
                    patches.addAll(proved.get());
                } catch (ExecutionException e) {
                    throw new IOException("couldn't check a patch: " + e.getCause(), e.getCause());
                }
            }
        }
        patches.sort(
                Comparator.comparing((Patch p) -> !p.safe())
                        .thenComparing(p -> -p.proof().cleared().size())
                        .thenComparing(p -> p.edit().key()));

        List<Patch> safe = patches.stream().filter(Patch::safe).toList();
        Optional<Proof> combined = Optional.empty();
        Set<String> cleared = new TreeSet<>();
        if (!safe.isEmpty()) {
            Set<String> targets = new TreeSet<>();
            safe.forEach(p -> targets.addAll(p.targets()));
            Proof together =
                    prove(
                            context,
                            safe.stream().map(Patch::edit).toList(),
                            safe.stream().flatMap(p -> p.changes().stream()).toList(),
                            targets,
                            baseline);
            combined = Optional.of(together);
            cleared.addAll(together.cleared());
        }
        List<Finding> remaining =
                vulnerabilities.stream()
                        .filter(
                                f ->
                                        vulnerabilityKey(f)
                                                .map(k -> !cleared.contains(k))
                                                .orElse(true))
                        .toList();
        return new CleanupPlan(patches, combined, remaining, secrets, baseline.size());
    }

    /** The candidate's patch, or its fallback pins when moving the BOM doesn't prove out. */
    private List<Patch> proveCandidate(
            RepoContext context, Candidate candidate, Set<String> baseline)
            throws InterruptedException {
        Proof proof =
                prove(
                        context,
                        List.of(candidate.edit()),
                        candidate.changes(),
                        candidate.targets(),
                        baseline);
        if (proof.accepted() || candidate.fallbacks().isEmpty()) {
            return List.of(patch(candidate, proof));
        }
        // Moving the whole BOM didn't work (e.g. there's no BOM release at that version): pin the
        // vulnerable artifacts one by one instead
        List<Patch> pins = new ArrayList<>();
        for (Candidate fallback : candidate.fallbacks()) {
            pins.add(
                    patch(
                            fallback,
                            prove(
                                    context,
                                    List.of(fallback.edit()),
                                    fallback.changes(),
                                    fallback.targets(),
                                    baseline)));
        }
        return pins;
    }

    private static Patch patch(Candidate candidate, Proof proof) {
        // Update is declared biggest first
        Versions.Update level =
                candidate.changes().stream()
                        .map(c -> Versions.update(c.from(), c.to()))
                        .min(Comparator.naturalOrder())
                        .orElse(Versions.Update.PATCH);
        return new Patch(
                candidate.edit(),
                candidate.changes(),
                candidate.alsoMoves().stream()
                        .filter(
                                key ->
                                        candidate.changes().stream()
                                                .noneMatch(c -> c.dependency().equals(key)))
                        .toList(),
                candidate.bom(),
                List.copyOf(candidate.targets()),
                candidate.crossesMajor(),
                level,
                proof);
    }

    /** Known vulnerabilities of the dependency tree under {@code root}, as {@code g:a ADVISORY}. */
    public Set<String> vulnerabilities(Path root, StealthConfig config)
            throws IOException, InterruptedException {
        try {
            return check.vulnerabilities(new RepoContext(root, config));
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Applies {@code edits} to a scratch copy of the POMs, re-resolves and checks again. */
    private Proof prove(
            RepoContext context,
            List<PomEdit> edits,
            List<Patch.VersionChange> changes,
            Set<String> targets,
            Set<String> baseline)
            throws InterruptedException {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("stealth-clean-");
            copyPoms(context.root(), scratch);
            PomEditor.applyAll(scratch, edits);
            RepoContext patched = new RepoContext(scratch, context.config());
            Optional<String> unresolved =
                    resolutionProblem(context.get(loader), patched.get(loader), changes)
                            .or(() -> unpublished(changes));
            if (unresolved.isPresent()) {
                return Proof.failed(unresolved.get());
            }
            Set<String> after = check.vulnerabilities(patched);
            List<String> cleared = targets.stream().filter(t -> !after.contains(t)).toList();
            List<String> remaining = targets.stream().filter(after::contains).toList();
            List<String> introduced =
                    after.stream().filter(v -> !baseline.contains(v)).sorted().toList();
            Proof.Status status =
                    !introduced.isEmpty() || cleared.isEmpty()
                            ? Proof.Status.REJECTED
                            : remaining.isEmpty() ? Proof.Status.PROVEN : Proof.Status.PARTIAL;
            return new Proof(status, cleared, remaining, introduced, Optional.empty());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return Proof.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (scratch != null) {
                deleteQuietly(scratch);
            }
        }
    }

    /**
     * Why the patched POMs don't resolve as intended, if they don't: a new resolution problem (a
     * version that isn't published, say), a module that lost its dependency tree, or a dependency
     * that doesn't end up at its new version. Without this, a dependency missing from the tree
     * would look like a fixed vulnerability.
     */
    static Optional<String> resolutionProblem(
            MavenProjectModel before, MavenProjectModel after, List<Patch.VersionChange> changes) {
        Set<String> knownWarnings = new java.util.HashSet<>(before.warnings());
        Optional<String> newWarning =
                after.warnings().stream().filter(w -> !knownWarnings.contains(w)).findFirst();
        if (newWarning.isPresent()) {
            return Optional.of("doesn't resolve: " + newWarning.get());
        }
        for (MavenModule module : before.modules()) {
            Optional<MavenModule> patched = after.module(module.directory());
            if (!module.dependencyTree().isEmpty()
                    && patched.map(m -> m.dependencyTree().isEmpty()).orElse(true)) {
                return Optional.of(
                        "the dependency tree of "
                                + (module.directory().isEmpty()
                                        ? "the root module"
                                        : module.directory())
                                + " no longer resolves");
            }
        }
        Map<String, Set<String>> resolved = resolvedVersions(after);
        Map<String, Set<String>> original = resolvedVersions(before);
        for (Patch.VersionChange change : changes) {
            Set<String> versions = resolved.getOrDefault(change.dependency(), Set.of());
            if (original.containsKey(change.dependency()) && !versions.contains(change.to())) {
                return Optional.of(
                        change.dependency()
                                + " resolves to "
                                + (versions.isEmpty() ? "nothing" : String.join(", ", versions))
                                + ", not "
                                + change.to());
            }
        }
        return Optional.empty();
    }

    /** The first new version that can't be resolved, e.g. a commercial-only release. */
    private Optional<String> unpublished(List<Patch.VersionChange> changes) {
        for (Patch.VersionChange change : changes) {
            String[] key = change.dependency().split(":", 2);
            if (key.length == 2 && !loader.isPublished(key[0], key[1], change.to())) {
                return Optional.of(
                        change.dependency()
                                + ":"
                                + change.to()
                                + " isn't published (OSV names it as the fix, but it can't be"
                                + " downloaded; commercial-only releases look like this)");
            }
        }
        return Optional.empty();
    }

    /** Every version of every dependency in the modules' resolved trees. */
    private static Map<String, Set<String>> resolvedVersions(MavenProjectModel model) {
        Map<String, Set<String>> versions = new LinkedHashMap<>();
        for (MavenModule module : model.modules()) {
            for (dev.stealth.core.maven.DependencyNode root : module.dependencyTree()) {
                root.walk(
                        path -> {
                            dev.stealth.core.maven.DependencyNode node = path.getLast();
                            versions.computeIfAbsent(node.key(), k -> new TreeSet<>())
                                    .add(node.version());
                        });
            }
        }
        return versions;
    }

    /** One vulnerable dependency version in one module, and what fixes its advisories. */
    private record Target(
            MavenModule module,
            String groupId,
            String artifactId,
            String version,
            Map<String, Optional<String>> fixedIn) {

        String key() {
            return groupId + ":" + artifactId;
        }

        /**
         * A same-major patch for what can be fixed in this major, a cross-major one for the rest.
         */
        List<Candidate> candidates(java.util.function.BiPredicate<String, String> isPublished) {
            List<Candidate> candidates = new ArrayList<>();
            Map<String, String> sameMajor = new LinkedHashMap<>();
            Map<String, String> newMajor = new LinkedHashMap<>();
            fixedIn.forEach(
                    (advisory, fixed) ->
                            fixed.filter(v -> isPublished.test(key(), v))
                                    .ifPresent(
                                            v ->
                                                    (Versions.update(version, v)
                                                                            == Versions.Update.MAJOR
                                                                    ? newMajor
                                                                    : sameMajor)
                                                            .put(advisory, v)));
            // A patch release that fixes what it can within the current minor line (Logback
            // 1.2.13 rather than 1.5.x): the safe option when the rest needs a minor jump
            Map<String, String> sameMinor = new LinkedHashMap<>();
            sameMajor.forEach(
                    (advisory, v) -> {
                        if (Versions.update(version, v) == Versions.Update.PATCH) {
                            sameMinor.put(advisory, v);
                        }
                    });
            if (!sameMinor.isEmpty() && sameMinor.size() < sameMajor.size()) {
                candidate(sameMinor, false).ifPresent(candidates::add);
            }
            candidate(sameMajor, false).ifPresent(candidates::add);
            // The cross-major patch has to fix the same-major advisories too
            Map<String, String> all = new LinkedHashMap<>(sameMajor);
            all.putAll(newMajor);
            if (!newMajor.isEmpty()) {
                candidate(all, true).ifPresent(candidates::add);
            }
            return candidates;
        }

        private Optional<Candidate> candidate(Map<String, String> fixes, boolean crossesMajor) {
            if (fixes.isEmpty()) {
                return Optional.empty();
            }
            String to = highest(fixes.values());
            Optional<PomEdit> edit = edit(to);
            if (edit.isEmpty()) {
                return Optional.empty();
            }
            Set<String> targets = new TreeSet<>();
            fixes.keySet().forEach(advisory -> targets.add(key() + " " + advisory));
            List<Patch.VersionChange> changes =
                    List.of(new Patch.VersionChange(key(), version, to));
            // Moving a BOM can fail (no BOM release at that version): then pin this artifact
            List<Candidate> fallbacks =
                    bom().isPresent() && !(edit.get() instanceof PomEdit.PinVersion)
                            ? List.of(
                                    new Candidate(
                                            new PomEdit.PinVersion(
                                                    topPom(), groupId, artifactId, to),
                                            changes,
                                            List.of(),
                                            targets,
                                            crossesMajor,
                                            Optional.empty(),
                                            List.of()))
                            : List.of();
            Optional<String> movesBom =
                    edit.get() instanceof PomEdit.PinVersion ? Optional.empty() : bom();
            return Optional.of(
                    new Candidate(
                            edit.get(),
                            changes,
                            movesBom.isPresent() ? List.of() : alsoMoves(edit.get()),
                            targets,
                            crossesMajor,
                            movesBom,
                            fallbacks));
        }

        /** The BOM managing this dependency, if one imported by the repo or a parent does. */
        private Optional<String> bom() {
            ManagedVersion managed = module.managedVersions().get(key());
            return managed == null ? Optional.empty() : managed.bom();
        }

        /** The cheapest edit that sets this dependency's version to {@code to}. */
        private Optional<PomEdit> edit(String to) {
            Optional<ResolvedDependency> direct = module.dependency(groupId, artifactId);
            if (direct.isPresent()
                    && direct.get().declaredAt().flatMap(Location::path).isPresent()
                    && direct.get().declaredAt().get().line().isPresent()
                    && (direct.get().versionSource() == VersionSource.DECLARED
                            || direct.get().versionSource() == VersionSource.MANAGED)) {
                Location at = direct.get().declaredAt().get();
                return Optional.of(
                        new PomEdit.SetVersion(at.path().get(), at.line().getAsInt(), version, to));
            }
            ManagedVersion managed = module.managedVersions().get(key());
            if (managed != null) {
                Optional<Location> here = managed.declaredAt();
                if (here.flatMap(Location::path).isPresent() && here.get().line().isPresent()) {
                    // The line sets the BOM's version when a BOM manages it, else the artifact's
                    String current =
                            managed.bom()
                                    .flatMap(
                                            bom ->
                                                    module.importedBoms().stream()
                                                            .filter(
                                                                    b ->
                                                                            (b.groupId()
                                                                                            + ":"
                                                                                            + b
                                                                                                    .artifactId())
                                                                                    .equals(bom))
                                                            .map(PomReference::version)
                                                            .findFirst())
                                    .orElse(managed.version());
                    return Optional.of(
                            new PomEdit.SetVersion(
                                    here.get().path().get(),
                                    here.get().line().getAsInt(),
                                    current,
                                    to));
                }
                if (managed.overridable() && managed.property().isPresent()) {
                    return Optional.of(
                            new PomEdit.SetProperty(topPom(), managed.property().get(), to));
                }
            }
            return Optional.of(new PomEdit.PinVersion(topPom(), groupId, artifactId, to));
        }

        /**
         * Other dependencies the edit moves too: everything the same BOM manages when the edit
         * moves a BOM, else everything managed by the same property.
         */
        private List<String> alsoMoves(PomEdit edit) {
            if (!(edit instanceof PomEdit.SetProperty property)) {
                return List.of();
            }
            return module.managedVersions().entrySet().stream()
                    .filter(e -> e.getValue().property().equals(Optional.of(property.name())))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }

        /**
         * The POM at the top of the module's hierarchy in this repository: the one declaring the
         * external parent, else the one importing BOMs, else the module's own.
         */
        private String topPom() {
            return module.parent()
                    .flatMap(PomReference::declaredAt)
                    .or(
                            () ->
                                    module.importedBoms().stream()
                                            .map(PomReference::declaredAt)
                                            .flatMap(Optional::stream)
                                            .findFirst())
                    .flatMap(Location::path)
                    .orElse(module.pomPath());
        }
    }

    private record Candidate(
            PomEdit edit,
            List<Patch.VersionChange> changes,
            List<String> alsoMoves,
            Set<String> targets,
            boolean crossesMajor,
            Optional<String> bom,
            List<Candidate> fallbacks) {

        /** The biggest move among the changes (Update is declared biggest first). */
        Versions.Update level() {
            return changes.stream()
                    .map(c -> Versions.update(c.from(), c.to()))
                    .min(Comparator.naturalOrder())
                    .orElse(Versions.Update.PATCH);
        }

        /** Two edits of the same thing: one edit at the higher version, covering both. */
        Candidate merge(Candidate other) {
            String value = highest(List.of(edit.value(), other.edit.value()));
            List<Patch.VersionChange> merged = new ArrayList<>();
            for (Patch.VersionChange change : changes) {
                merged.add(new Patch.VersionChange(change.dependency(), change.from(), value));
            }
            for (Patch.VersionChange change : other.changes) {
                if (merged.stream().noneMatch(c -> c.dependency().equals(change.dependency()))) {
                    merged.add(new Patch.VersionChange(change.dependency(), change.from(), value));
                }
            }
            Set<String> targets = new TreeSet<>(this.targets);
            targets.addAll(other.targets);
            Set<String> alsoMoves = new LinkedHashSet<>(this.alsoMoves);
            alsoMoves.addAll(other.alsoMoves);
            Map<String, Candidate> fallbacks = new LinkedHashMap<>();
            for (Candidate fallback : this.fallbacks) {
                fallbacks.merge(fallback.edit().key(), fallback, Candidate::merge);
            }
            for (Candidate fallback : other.fallbacks) {
                fallbacks.merge(fallback.edit().key(), fallback, Candidate::merge);
            }
            return new Candidate(
                    edit.withValue(value),
                    merged,
                    List.copyOf(alsoMoves),
                    targets,
                    crossesMajor || other.crossesMajor,
                    bom.or(() -> other.bom),
                    List.copyOf(fallbacks.values()));
        }
    }

    /** Vulnerable dependency versions per module, from doctor's findings. */
    private static List<Target> targets(List<Finding> findings, MavenProjectModel model) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Finding finding : findings) {
            Optional<String> purl = finding.component();
            if (purl.isEmpty() || finding.advisory().isEmpty()) {
                continue;
            }
            String[] coordinates = coordinates(purl.get());
            if (coordinates == null) {
                continue;
            }
            Optional<MavenModule> module = module(finding.location(), model);
            if (module.isEmpty()) {
                continue;
            }
            String key = module.get().directory() + "|" + String.join(":", coordinates);
            Target target =
                    targets.computeIfAbsent(
                            key,
                            k ->
                                    new Target(
                                            module.get(),
                                            coordinates[0],
                                            coordinates[1],
                                            coordinates[2],
                                            new LinkedHashMap<>()));
            target.fixedIn()
                    .putIfAbsent(
                            finding.advisory().get().id(),
                            finding.remediation().flatMap(Remediation::fixedVersion));
        }
        return List.copyOf(targets.values());
    }

    private static Optional<MavenModule> module(Location location, MavenProjectModel model) {
        Optional<MavenModule> byDirectory = location.module().flatMap(model::module);
        if (byDirectory.isPresent()) {
            return byDirectory;
        }
        Optional<MavenModule> byPom =
                location.path()
                        .flatMap(
                                path ->
                                        model.modules().stream()
                                                .filter(m -> m.pomPath().equals(path))
                                                .findFirst());
        return byPom.or(() -> model.module(""));
    }

    /** {@code pkg:maven/g/a@v} → {g, a, v}. */
    private static String[] coordinates(String purl) {
        if (!purl.startsWith("pkg:maven/") || !purl.contains("@")) {
            return null;
        }
        String path = purl.substring("pkg:maven/".length(), purl.lastIndexOf('@'));
        int slash = path.indexOf('/');
        if (slash < 0) {
            return null;
        }
        return new String[] {
            path.substring(0, slash),
            path.substring(slash + 1),
            purl.substring(purl.lastIndexOf('@') + 1)
        };
    }

    /** {@code g:a ADVISORY-ID} for a vulnerability finding. */
    static Optional<String> vulnerabilityKey(Finding finding) {
        if (!finding.ruleId().equals(RULE)
                || finding.component().isEmpty()
                || finding.advisory().isEmpty()) {
            return Optional.empty();
        }
        String[] coordinates = coordinates(finding.component().get());
        return coordinates == null
                ? Optional.empty()
                : Optional.of(
                        coordinates[0]
                                + ":"
                                + coordinates[1]
                                + " "
                                + finding.advisory().get().id());
    }

    private static String highest(java.util.Collection<String> versions) {
        return versions.stream().max(Comparator.comparing(ComparableVersion::new)).orElseThrow();
    }

    /** The POMs and {@code .mvn} configuration: all the model loader reads. */
    private static void copyPoms(Path root, Path scratch) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(root)
                                && (name.equals(".git")
                                        || name.equals("target")
                                        || name.equals("node_modules"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = root.relativize(file);
                        boolean pom = file.getFileName().toString().equals("pom.xml");
                        boolean mavenConfig = relative.startsWith(".mvn");
                        if (pom || mavenConfig) {
                            Path copy = scratch.resolve(relative.toString());
                            Files.createDirectories(copy.getParent());
                            Files.copy(file, copy);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static void deleteQuietly(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    // A temp directory; the OS cleans it up eventually
                                }
                            });
        } catch (IOException | UncheckedIOException e) {
            // Same
        }
    }
}
