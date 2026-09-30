package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3ReaderEx;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * The repository's own POMs as written (not the effective models), with line numbers, found by
 * walking {@code <modules>} from the root POM.
 */
final class RepoPoms {

    private final Path root;
    private final Map<Path, Model> models = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private RepoPoms(Path root) {
        this.root = root;
    }

    /** Reads the root POM and its modules, recursively. Unreadable POMs become warnings. */
    static RepoPoms discover(Path root) {
        RepoPoms poms = new RepoPoms(root.toAbsolutePath().normalize());
        Path rootPom = poms.root.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            poms.read(rootPom);
        }
        return poms;
    }

    private void read(Path pom) {
        pom = pom.normalize();
        if (models.containsKey(pom)) {
            return;
        }
        Model model;
        try (InputStream in = Files.newInputStream(pom)) {
            InputSource source = new InputSource();
            source.setLocation(pom.toString());
            model = new MavenXpp3ReaderEx().read(in, false, source);
        } catch (IOException | XmlPullParserException e) {
            warnings.add(relative(pom) + ": can't read POM: " + e.getMessage());
            return;
        }
        model.setPomFile(pom.toFile());
        models.put(pom, model);
        for (String module : model.getModules()) {
            Path target = pom.getParent().resolve(module);
            read(Files.isDirectory(target) ? target.resolve("pom.xml") : target);
        }
    }

    Path root() {
        return root;
    }

    /** The POM files, root first, then modules depth-first in {@code <modules>} order. */
    List<Path> pomFiles() {
        return List.copyOf(models.keySet());
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }

    boolean contains(Path pom) {
        return models.containsKey(pom.toAbsolutePath().normalize());
    }

    Model raw(Path pom) {
        return models.get(pom.toAbsolutePath().normalize());
    }

    /**
     * {@code groupId:artifactId:version} of every module, for telling internal dependencies apart.
     */
    Map<String, Path> modulesByCoordinates() {
        Map<String, Path> byCoordinates = new LinkedHashMap<>();
        models.forEach((pom, model) -> byCoordinates.put(coordinates(model), pom));
        return byCoordinates;
    }

    /**
     * {@code pom} and the parents above it that are also in this repository, nearest first. The
     * last one's parent, if any, is outside the repository.
     */
    List<Path> hierarchy(Path pom) {
        List<Path> chain = new ArrayList<>();
        Path current = pom.toAbsolutePath().normalize();
        while (current != null && models.containsKey(current) && !chain.contains(current)) {
            chain.add(current);
            current = repoParent(current).orElse(null);
        }
        return chain;
    }

    /** The parent POM of {@code pom} if it's in this repository. */
    Optional<Path> repoParent(Path pom) {
        Model model = raw(pom);
        Parent parent = model.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        String relativePath = parent.getRelativePath();
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        Path candidate = pom.getParent().resolve(relativePath).normalize();
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve("pom.xml");
        }
        Model candidateModel = models.get(candidate);
        if (candidateModel == null) {
            // Not a module of this build, but still a POM in the repository
            if (!Files.isRegularFile(candidate) || !candidate.startsWith(root)) {
                return Optional.empty();
            }
            read(candidate);
            candidateModel = models.get(candidate);
            if (candidateModel == null) {
                return Optional.empty();
            }
        }
        boolean matches =
                parent.getArtifactId().equals(candidateModel.getArtifactId())
                        && parent.getGroupId().equals(groupId(candidateModel));
        return matches ? Optional.of(candidate) : Optional.empty();
    }

    /** The {@code <parent>} of the top of {@code pom}'s hierarchy, if it's outside the repo. */
    Optional<Parent> externalParent(Path pom) {
        List<Path> chain = hierarchy(pom);
        Model top = raw(chain.getLast());
        return repoParent(chain.getLast()).isPresent()
                ? Optional.empty()
                : Optional.ofNullable(top.getParent());
    }

    /** Repo-relative, forward slashes. */
    String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The module directory containing {@code pom}, repo-relative ({@code ""} for the root). */
    String moduleDirectory(Path pom) {
        Path dir = pom.toAbsolutePath().normalize().getParent();
        return root.equals(dir) ? "" : relative(dir);
    }

    /** A {@link Location} for a line of one of this repository's POMs. */
    Optional<Location> location(Path pom, InputLocation inputLocation) {
        if (inputLocation == null || inputLocation.getLineNumber() < 1) {
            return Optional.empty();
        }
        return Optional.of(
                new Location(
                        Optional.of(relative(pom)),
                        OptionalInt.of(inputLocation.getLineNumber()),
                        Optional.of(moduleDirectory(pom))));
    }

    static String groupId(Model model) {
        if (model.getGroupId() != null) {
            return model.getGroupId();
        }
        return model.getParent() == null ? "" : model.getParent().getGroupId();
    }

    static String version(Model model) {
        if (model.getVersion() != null) {
            return model.getVersion();
        }
        return model.getParent() == null ? "" : model.getParent().getVersion();
    }

    static String coordinates(Model model) {
        return groupId(model) + ":" + model.getArtifactId() + ":" + version(model);
    }
}
