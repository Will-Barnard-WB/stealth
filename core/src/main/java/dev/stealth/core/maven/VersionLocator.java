package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;

/**
 * Finds the line in this repository that sets a version, following the model builder's location
 * tracking back through properties, BOM imports and external parents. See the effective-versions
 * table in {@code fixtures/README.md} for the cases this has to get right.
 */
final class VersionLocator {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_PROPERTY_DEPTH = 5;

    private final RepoPoms poms;
    private final Map<Path, List<String>> lines = new ConcurrentHashMap<>();

    VersionLocator(RepoPoms poms) {
        this.poms = poms;
    }

    record Located(Optional<Location> location, VersionSource source) {

        static final Located UNKNOWN = new Located(Optional.empty(), VersionSource.UNKNOWN);
    }

    /**
     * Where the version of {@code dependency}, from the effective model of the module at {@code
     * modulePom}, is set.
     */
    Located locate(Dependency dependency, Path modulePom) {
        InputLocation version = dependency.getLocation("version");
        if (version == null || version.getSource() == null) {
            return Located.UNKNOWN;
        }
        Optional<Path> sourcePom = repoPom(version.getSource().getLocation());
        if (sourcePom.isPresent()) {
            VersionSource source =
                    declaresVersion(poms.raw(sourcePom.get()), dependency)
                            ? VersionSource.DECLARED
                            : VersionSource.MANAGED;
            return new Located(followValue(sourcePom.get(), version, modulePom), source);
        }
        return locateExternal(version.getSource().getModelId(), modulePom);
    }

    /**
     * A version managed outside the repository: by a BOM this repository imports, or by an external
     * parent (and whatever that parent imports).
     */
    private Located locateExternal(String modelId, Path modulePom) {
        String groupAndArtifact = groupAndArtifact(modelId);
        int imports = 0;
        Located onlyImport = Located.UNKNOWN;
        for (Path pom : poms.hierarchy(modulePom)) {
            Model raw = poms.raw(pom);
            if (raw.getDependencyManagement() == null) {
                continue;
            }
            for (Dependency managed : raw.getDependencyManagement().getDependencies()) {
                if (!"import".equals(managed.getScope())) {
                    continue;
                }
                Located located =
                        new Located(
                                followValue(pom, managed.getLocation("version"), modulePom),
                                VersionSource.BOM);
                if ((managed.getGroupId() + ":" + managed.getArtifactId())
                        .equals(groupAndArtifact)) {
                    return located;
                }
                imports++;
                onlyImport = located;
            }
        }
        Optional<Located> parent = externalParent(modulePom);
        if (parent.isPresent()) {
            return parent.get();
        }
        // Managed by a BOM that one of our imports imports in turn
        return imports == 1 ? onlyImport : Located.UNKNOWN;
    }

    /** The {@code <version>} of the external parent at the top of the module's hierarchy. */
    Optional<Located> externalParent(Path modulePom) {
        Optional<Parent> parent = poms.externalParent(modulePom);
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        Path top = poms.hierarchy(modulePom).getLast();
        return Optional.of(
                new Located(
                        followValue(top, parent.get().getLocation("version"), modulePom),
                        VersionSource.PARENT));
    }

    /**
     * Where the property {@code name} is defined, as the module at {@code modulePom} sees it: the
     * module's own definition wins over its parents', as in Maven's interpolation.
     */
    Optional<Location> locateProperty(String name, Path modulePom) {
        return locateProperty(name, modulePom, 0);
    }

    private Optional<Location> locateProperty(String name, Path modulePom, int depth) {
        if (depth > MAX_PROPERTY_DEPTH) {
            return Optional.empty();
        }
        if (name.equals("project.version") || name.equals("version")) {
            return projectVersion(modulePom);
        }
        for (Path pom : poms.hierarchy(modulePom)) {
            Model raw = poms.raw(pom);
            if (!raw.getProperties().containsKey(name)) {
                continue;
            }
            // The value may itself be a property: the version is written where that one is set
            Matcher nested = PROPERTY.matcher(raw.getProperties().getProperty(name));
            if (nested.find()) {
                return locateProperty(nested.group(1), modulePom, depth + 1);
            }
            InputLocation properties = raw.getLocation("properties");
            return poms.location(pom, properties == null ? null : properties.getLocation(name));
        }
        // Defined only outside the repository, typically by the external parent
        return externalParent(modulePom).flatMap(Located::location);
    }

    private Optional<Location> projectVersion(Path modulePom) {
        for (Path pom : poms.hierarchy(modulePom)) {
            Model raw = poms.raw(pom);
            if (raw.getVersion() != null) {
                return poms.location(pom, raw.getLocation("version"));
            }
            if (raw.getParent() != null && raw.getParent().getVersion() != null) {
                return poms.location(pom, raw.getParent().getLocation("version"));
            }
        }
        return Optional.empty();
    }

    /**
     * The location itself, or, if the value written there is a {@code ${property}}, where that
     * property is set.
     */
    private Optional<Location> followValue(Path pom, InputLocation location, Path modulePom) {
        if (location == null) {
            return Optional.empty();
        }
        String text = line(pom, location.getLineNumber());
        Matcher property = PROPERTY.matcher(text);
        if (property.find()) {
            return locateProperty(property.group(1), modulePom);
        }
        return poms.location(pom, location);
    }

    private Optional<Path> repoPom(String sourceLocation) {
        if (sourceLocation == null) {
            return Optional.empty();
        }
        Path path = Path.of(sourceLocation).toAbsolutePath().normalize();
        return poms.contains(path) ? Optional.of(path) : Optional.empty();
    }

    private static boolean declaresVersion(Model raw, Dependency dependency) {
        return raw.getDependencies().stream()
                .anyMatch(
                        d ->
                                d.getGroupId().equals(dependency.getGroupId())
                                        && d.getArtifactId().equals(dependency.getArtifactId())
                                        && d.getVersion() != null);
    }

    private String line(Path pom, int lineNumber) {
        List<String> text =
                lines.computeIfAbsent(
                        pom,
                        file -> {
                            try {
                                return Files.readAllLines(file);
                            } catch (IOException e) {
                                return List.of();
                            }
                        });
        return lineNumber >= 1 && lineNumber <= text.size() ? text.get(lineNumber - 1) : "";
    }

    /** {@code groupId:artifactId} from a model id, which is {@code groupId:artifactId:version}. */
    private static String groupAndArtifact(String modelId) {
        if (modelId == null) {
            return "";
        }
        int last = modelId.lastIndexOf(':');
        return last < 0 ? modelId : modelId.substring(0, last);
    }
}
