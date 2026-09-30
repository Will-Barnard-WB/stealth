package dev.stealth.core.maven;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One module of the build, with versions as Maven itself would resolve them.
 *
 * @param directory repo-relative module directory, {@code ""} for the root
 * @param pomPath repo-relative path of the module's {@code pom.xml}
 * @param dependencies the module's direct dependencies from its effective model, including ones
 *     inherited from a parent's {@code <dependencies>}
 * @param dependencyTree the direct dependencies with their transitive dependencies, after Maven's
 *     nearest-wins conflict resolution; empty if the tree couldn't be collected
 * @param properties the effective model's properties
 * @param javaVersion the Java release the module compiles for
 * @param springBootVersion the Spring Boot version the module uses, if any
 * @param parent the external parent at the top of the module's hierarchy, if any
 * @param importedBoms BOMs imported by the module or its parents in this repository
 */
public record MavenModule(
        String groupId,
        String artifactId,
        String version,
        String directory,
        String pomPath,
        List<ResolvedDependency> dependencies,
        List<DependencyNode> dependencyTree,
        Map<String, String> properties,
        Optional<DeclaredVersion> javaVersion,
        Optional<DeclaredVersion> springBootVersion,
        Optional<PomReference> parent,
        List<PomReference> importedBoms) {

    public MavenModule {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(pomPath, "pomPath");
        dependencies = List.copyOf(dependencies);
        dependencyTree = List.copyOf(dependencyTree);
        properties = Map.copyOf(properties);
        Objects.requireNonNull(javaVersion, "javaVersion");
        Objects.requireNonNull(springBootVersion, "springBootVersion");
        Objects.requireNonNull(parent, "parent");
        importedBoms = List.copyOf(importedBoms);
    }

    /** The direct dependency {@code groupId:artifactId}, if the module has one. */
    public Optional<ResolvedDependency> dependency(String groupId, String artifactId) {
        return dependencies.stream()
                .filter(d -> d.groupId().equals(groupId) && d.artifactId().equals(artifactId))
                .findFirst();
    }
}
