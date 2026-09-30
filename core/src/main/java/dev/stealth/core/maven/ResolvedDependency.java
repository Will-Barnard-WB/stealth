package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.util.Objects;
import java.util.Optional;

/**
 * A direct dependency of a module, with its effective version.
 *
 * @param scope Maven scope, {@code compile} when the POM doesn't say
 * @param internal whether it's another module of this repository, which is never looked up on Maven
 *     Central or OSV
 * @param declaredAt the line in this repository that sets the version: the {@code <version>}
 *     element, or the property it uses, or the BOM import or parent that manages it. Empty for
 *     internal modules and when the version comes from somewhere stealth can't trace
 * @param versionSource how the version was set
 */
public record ResolvedDependency(
        String groupId,
        String artifactId,
        String version,
        String scope,
        boolean internal,
        Optional<Location> declaredAt,
        VersionSource versionSource) {

    public ResolvedDependency {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(declaredAt, "declaredAt");
        Objects.requireNonNull(versionSource, "versionSource");
    }

    /** {@code groupId:artifactId}. */
    public String key() {
        return groupId + ":" + artifactId;
    }
}
