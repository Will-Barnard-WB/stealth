package dev.stealth.core.maven;

import dev.stealth.core.Location;
import dev.stealth.core.RepoContext;
import dev.stealth.core.SharedResource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelCache;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

/**
 * Builds the {@link MavenProjectModel} of the repository being analyzed. It's a {@link
 * SharedResource}, so every analyzer in a run shares one model: {@code context.get(loader)}.
 *
 * <p>Effective models come from Maven's own model builder, so parents, BOM imports, properties and
 * profiles resolve exactly as in a real build. Anything that can't be resolved becomes a warning in
 * the model; loading never throws because of the repository's contents.
 */
public class MavenModelLoader implements SharedResource<MavenProjectModel> {

    private static final String SPRING_BOOT_GROUP = "org.springframework.boot";

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    // Most specific first: release beats source, which beats Spring Boot's java.version
    private static final List<String> JAVA_VERSION_PROPERTIES =
            List.of(
                    "maven.compiler.release",
                    "maven.compiler.source",
                    "maven.compiler.target",
                    "java.version");

    private final Supplier<MavenResolverSettings> settings;
    private final ReentrantLock lock = new ReentrantLock();

    // Created on first load: building the resolver is slow, and most CLI runs never load a model
    private RepositorySystem system;
    private ModelBuilder modelBuilder;

    public MavenModelLoader(MavenResolverSettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.settings = () -> settings;
    }

    /** Reads {@code settings} at the start of each load, so a CLI flag can switch to offline. */
    public MavenModelLoader(Supplier<MavenResolverSettings> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public MavenProjectModel load(RepoContext context) {
        return load(context.root(), context.config()::isIgnored);
    }

    public MavenProjectModel load(Path repositoryRoot) {
        return load(repositoryRoot, directory -> false);
    }

    /**
     * Skips modules whose directory {@code ignored} matches (ADR-0004: a glob matching a module
     * directory excludes the module, dependencies included). The root module is never skipped.
     */
    public MavenProjectModel load(Path repositoryRoot, Predicate<String> ignored) {
        RepoPoms poms = RepoPoms.discover(repositoryRoot);
        if (poms.pomFiles().isEmpty()) {
            return new MavenProjectModel(List.of(), poms.warnings());
        }
        start();
        return new Run(poms, settings.get(), ignored).load();
    }

    private void start() {
        lock.lock();
        try {
            if (system == null) {
                system = new RepositorySystemSupplier().get();
                modelBuilder = new DefaultModelBuilderFactory().newInstance();
            }
        } finally {
            lock.unlock();
        }
    }

    /** One load: the resolver session and caches are shared by every module of the repository. */
    private final class Run {

        private final RepoPoms poms;
        private final MavenResolverSettings settings;
        private final Predicate<String> ignored;
        private final VersionLocator locator;
        private final Map<String, Path> modules;
        private final DefaultRepositorySystemSession session;
        private final List<RemoteRepository> repositories;
        private final ResolverModelResolver modelResolver;
        private final ModelCache modelCache = new MapModelCache();
        private final Properties systemProperties = new Properties();
        private final List<String> warnings = new ArrayList<>();

        Run(RepoPoms poms, MavenResolverSettings settings, Predicate<String> ignored) {
            this.poms = poms;
            this.settings = settings;
            this.ignored = ignored;
            this.locator = new VersionLocator(poms);
            this.modules = poms.modulesByCoordinates();
            this.session = session();
            this.repositories =
                    List.of(
                            new RemoteRepository.Builder(
                                            "central",
                                            "default",
                                            settings.remoteRepository().toString())
                                    .setSnapshotPolicy(new RepositoryPolicy(false, null, null))
                                    .build());
            this.modelResolver = new ResolverModelResolver(system, session, repositories, modules);
            this.systemProperties.putAll(System.getProperties());
            this.warnings.addAll(poms.warnings());
        }

        MavenProjectModel load() {
            List<MavenModule> loaded = new ArrayList<>();
            for (Path pom : poms.pomFiles()) {
                String directory = poms.moduleDirectory(pom);
                if (!directory.isEmpty() && ignored.test(directory)) {
                    continue;
                }
                build(pom).ifPresent(loaded::add);
            }
            return new MavenProjectModel(loaded, warnings);
        }

        private DefaultRepositorySystemSession session() {
            DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
            session.setLocalRepositoryManager(
                    system.newLocalRepositoryManager(
                            session, new LocalRepository(settings.localRepository().toFile())));
            session.setOffline(settings.offline());
            session.setWorkspaceReader(new ReactorReader(poms.modulesByCoordinates()));
            session.setIgnoreArtifactDescriptorRepositories(true);
            session.setSystemProperties(System.getProperties());
            session.setConfigProperty(ConfigurationProperties.USER_AGENT, "stealth");
            session.setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, 10_000);
            session.setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, 30_000);
            return session;
        }

        private Optional<MavenModule> build(Path pom) {
            Model raw = poms.raw(pom);
            String pomPath = poms.relative(pom);
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setPomFile(pom.toFile())
                    .setModelResolver(modelResolver)
                    .setModelCache(modelCache)
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                    .setProcessPlugins(false)
                    .setTwoPhaseBuilding(false)
                    .setLocationTracking(true)
                    .setSystemProperties(systemProperties);

            Model effective;
            ModelBuildingResult result;
            try {
                result = modelBuilder.build(request);
                effective = result.getEffectiveModel();
            } catch (ModelBuildingException e) {
                for (ModelProblem problem : e.getProblems()) {
                    if (problem.getSeverity() != ModelProblem.Severity.WARNING) {
                        warnings.add(pomPath + ": " + problem.getMessage());
                    }
                }
                // Keep the module, with what the POM itself says, so analyzers still see it
                return Optional.of(unresolvedModule(pom, raw));
            }

            List<ResolvedDependency> dependencies = new ArrayList<>();
            for (Dependency dependency : effective.getDependencies()) {
                resolvedDependency(dependency, pom).ifPresent(dependencies::add);
            }
            return Optional.of(
                    new MavenModule(
                            effective.getGroupId(),
                            effective.getArtifactId(),
                            effective.getVersion(),
                            poms.moduleDirectory(pom),
                            pomPath,
                            dependencies,
                            dependencyTree(effective, pomPath),
                            propertiesOf(effective),
                            javaVersion(effective, pom),
                            springBootVersion(effective, pom),
                            externalParent(pom),
                            importedBoms(pom, effective),
                            managedVersions(effective, result, pom)));
        }

        private Optional<PomReference> externalParent(Path pom) {
            return poms.externalParent(pom)
                    .map(
                            parent ->
                                    new PomReference(
                                            parent.getGroupId(),
                                            parent.getArtifactId(),
                                            parent.getVersion(),
                                            locator.externalParent(pom)
                                                    .flatMap(VersionLocator.Located::location)));
        }

        /**
         * Imports as written, with {@code ${property}} versions filled in from the effective model.
         */
        private List<PomReference> importedBoms(Path pom, Model effective) {
            List<PomReference> boms = new ArrayList<>();
            for (Path declaring : poms.hierarchy(pom)) {
                Model raw = poms.raw(declaring);
                if (raw.getDependencyManagement() == null) {
                    continue;
                }
                for (Dependency managed : raw.getDependencyManagement().getDependencies()) {
                    if ("import".equals(managed.getScope()) && managed.getVersion() != null) {
                        boms.add(
                                new PomReference(
                                        managed.getGroupId(),
                                        managed.getArtifactId(),
                                        interpolate(managed.getVersion(), effective),
                                        locator.followValue(
                                                declaring, managed.getLocation("version"), pom)));
                    }
                }
            }
            return boms;
        }

        private String interpolate(String value, Model effective) {
            Matcher property = PROPERTY.matcher(value);
            StringBuilder result = new StringBuilder();
            while (property.find()) {
                String name = property.group(1);
                String replacement =
                        name.equals("project.version") || name.equals("version")
                                ? effective.getVersion()
                                : effective.getProperties().getProperty(name, property.group());
                property.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            return property.appendTail(result).toString();
        }

        private Optional<ResolvedDependency> resolvedDependency(Dependency dependency, Path pom) {
            if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
                warnings.add(
                        poms.relative(pom)
                                + ": no version for "
                                + dependency.getGroupId()
                                + ":"
                                + dependency.getArtifactId());
                return Optional.empty();
            }
            boolean internal = modules.containsKey(coordinates(dependency));
            VersionLocator.Located located =
                    internal
                            ? new VersionLocator.Located(Optional.empty(), VersionSource.INTERNAL)
                            : locator.locate(dependency, pom);
            return Optional.of(
                    new ResolvedDependency(
                            dependency.getGroupId(),
                            dependency.getArtifactId(),
                            dependency.getVersion(),
                            dependency.getScope() == null ? "compile" : dependency.getScope(),
                            internal,
                            located.location(),
                            located.source()));
        }

        /** The effective {@code <dependencyManagement>}, without BOM imports (already expanded). */
        private Map<String, ManagedVersion> managedVersions(
                Model effective, ModelBuildingResult result, Path pom) {
            Map<String, ManagedVersion> managed = new LinkedHashMap<>();
            if (effective.getDependencyManagement() == null) {
                return managed;
            }
            Set<String> parents = new HashSet<>(result.getModelIds());
            // BOM imports as the module and its parents write them, nearest first
            Map<String, VersionLocator.BomImport> imports = new LinkedHashMap<>();
            for (String id : result.getModelIds()) {
                Model raw = result.getRawModel(id);
                if (raw == null || raw.getDependencyManagement() == null) {
                    continue;
                }
                Optional<Path> declaring =
                        raw.getPomFile() == null
                                ? Optional.empty()
                                : locator.repositoryPom(raw.getPomFile().getPath());
                for (Dependency imported : raw.getDependencyManagement().getDependencies()) {
                    if ("import".equals(imported.getScope())) {
                        imports.putIfAbsent(
                                imported.getGroupId() + ":" + imported.getArtifactId(),
                                new VersionLocator.BomImport(
                                        imported.getVersion(),
                                        imported.getLocation("version"),
                                        declaring));
                    }
                }
            }
            for (Dependency dependency : effective.getDependencyManagement().getDependencies()) {
                if (dependency.getVersion() == null || "import".equals(dependency.getScope())) {
                    continue;
                }
                managed.putIfAbsent(
                        dependency.getGroupId() + ":" + dependency.getArtifactId(),
                        locator.managed(dependency, parents, imports, pom));
            }
            return managed;
        }

        private MavenModule unresolvedModule(Path pom, Model raw) {
            return new MavenModule(
                    RepoPoms.groupId(raw),
                    raw.getArtifactId(),
                    RepoPoms.version(raw),
                    poms.moduleDirectory(pom),
                    poms.relative(pom),
                    List.of(),
                    List.of(),
                    propertiesOf(raw),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Map.of());
        }

        private List<DependencyNode> dependencyTree(Model effective, String pomPath) {
            CollectRequest request = new CollectRequest();
            request.setRootArtifact(
                    new DefaultArtifact(
                            effective.getGroupId(),
                            effective.getArtifactId(),
                            "",
                            "pom",
                            effective.getVersion()));
            request.setRepositories(repositories);
            effective.getDependencies().stream()
                    .filter(d -> d.getVersion() != null)
                    .map(this::toAether)
                    .forEach(request::addDependency);
            if (effective.getDependencyManagement() != null) {
                effective.getDependencyManagement().getDependencies().stream()
                        .filter(d -> d.getVersion() != null)
                        .map(this::toAether)
                        .forEach(request::addManagedDependency);
            }

            org.eclipse.aether.graph.DependencyNode root;
            try {
                CollectResult result = system.collectDependencies(session, request);
                root = result.getRoot();
            } catch (DependencyCollectionException e) {
                warnings.add(pomPath + ": incomplete dependency tree: " + e.getMessage());
                root = e.getResult().getRoot();
            }
            return root == null ? List.of() : children(root, new HashSet<>());
        }

        private List<DependencyNode> children(
                org.eclipse.aether.graph.DependencyNode node, Set<String> path) {
            List<DependencyNode> children = new ArrayList<>();
            for (org.eclipse.aether.graph.DependencyNode child : node.getChildren()) {
                var dependency = child.getDependency();
                if (dependency == null) {
                    continue;
                }
                var artifact = dependency.getArtifact();
                String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
                if (!path.add(key)) {
                    continue; // a cycle
                }
                children.add(
                        new DependencyNode(
                                artifact.getGroupId(),
                                artifact.getArtifactId(),
                                artifact.getBaseVersion(),
                                dependency.getScope().isEmpty() ? "compile" : dependency.getScope(),
                                children(child, path)));
                path.remove(key);
            }
            return children;
        }

        private org.eclipse.aether.graph.Dependency toAether(Dependency dependency) {
            String type = dependency.getType() == null ? "jar" : dependency.getType();
            ArtifactType artifactType = session.getArtifactTypeRegistry().get(type);
            if (artifactType == null) {
                artifactType = new DefaultArtifactType(type);
            }
            String classifier =
                    dependency.getClassifier() == null
                            ? artifactType.getClassifier()
                            : dependency.getClassifier();
            var artifact =
                    new DefaultArtifact(
                            dependency.getGroupId(),
                            dependency.getArtifactId(),
                            classifier,
                            artifactType.getExtension(),
                            dependency.getVersion(),
                            artifactType);
            List<Exclusion> exclusions =
                    dependency.getExclusions().stream()
                            .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                            .toList();
            return new org.eclipse.aether.graph.Dependency(
                    artifact,
                    dependency.getScope(),
                    dependency.isOptional() ? Boolean.TRUE : null,
                    exclusions);
        }

        /**
         * The compiler plugin's {@code <release>} if set, otherwise the first property of {@link
         * #JAVA_VERSION_PROPERTIES} the effective model defines. The location is the first of those
         * properties this repository sets.
         */
        private Optional<DeclaredVersion> javaVersion(Model effective, Path pom) {
            Optional<String> value = compilerPluginRelease(effective);
            if (value.isEmpty()) {
                value =
                        JAVA_VERSION_PROPERTIES.stream()
                                .map(effective.getProperties()::getProperty)
                                .filter(v -> v != null && !v.isBlank() && !v.contains("${"))
                                .findFirst();
            }
            if (value.isEmpty()) {
                return Optional.empty();
            }
            Optional<Location> declaredAt =
                    JAVA_VERSION_PROPERTIES.stream()
                            .filter(name -> definedInRepository(name, pom))
                            .findFirst()
                            .flatMap(name -> locator.locateProperty(name, pom));
            return Optional.of(new DeclaredVersion(value.get(), declaredAt));
        }

        private boolean definedInRepository(String property, Path pom) {
            return poms.hierarchy(pom).stream()
                    .anyMatch(p -> poms.raw(p).getProperties().containsKey(property));
        }

        private Optional<String> compilerPluginRelease(Model effective) {
            if (effective.getBuild() == null) {
                return Optional.empty();
            }
            for (Plugin plugin : effective.getBuild().getPlugins()) {
                if ("maven-compiler-plugin".equals(plugin.getArtifactId())
                        && plugin.getConfiguration() instanceof Xpp3Dom configuration
                        && configuration.getChild("release") != null) {
                    String release = configuration.getChild("release").getValue();
                    if (release != null && !release.contains("${")) {
                        return Optional.of(release.trim());
                    }
                }
            }
            return Optional.empty();
        }

        /**
         * From the version of {@code spring-boot} that the build manages (through the Boot parent
         * or BOM), falling back to any Spring Boot dependency with a version.
         */
        private Optional<DeclaredVersion> springBootVersion(Model effective, Path pom) {
            List<Dependency> candidates = new ArrayList<>();
            if (effective.getDependencyManagement() != null) {
                effective.getDependencyManagement().getDependencies().stream()
                        .filter(d -> SPRING_BOOT_GROUP.equals(d.getGroupId()))
                        .filter(d -> "spring-boot".equals(d.getArtifactId()))
                        .forEach(candidates::add);
            }
            effective.getDependencies().stream()
                    .filter(d -> SPRING_BOOT_GROUP.equals(d.getGroupId()))
                    .forEach(candidates::add);
            return candidates.stream()
                    .filter(d -> d.getVersion() != null)
                    .findFirst()
                    .map(
                            d ->
                                    new DeclaredVersion(
                                            d.getVersion(), locator.locate(d, pom).location()));
        }

        private Map<String, String> propertiesOf(Model model) {
            Map<String, String> properties = new LinkedHashMap<>();
            model.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
            return properties;
        }
    }

    private static String coordinates(Dependency dependency) {
        return dependency.getGroupId()
                + ":"
                + dependency.getArtifactId()
                + ":"
                + dependency.getVersion();
    }

    /** Shares parsed parents and BOMs between the modules of one repository. */
    private static final class MapModelCache implements ModelCache {

        private final Map<List<String>, Object> entries = new ConcurrentHashMap<>();

        @Override
        public void put(
                String groupId, String artifactId, String version, String tag, Object data) {
            entries.put(List.of(groupId, artifactId, version, tag), data);
        }

        @Override
        public Object get(String groupId, String artifactId, String version, String tag) {
            return entries.get(List.of(groupId, artifactId, version, tag));
        }
    }
}
