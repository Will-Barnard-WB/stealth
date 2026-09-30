package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.util.Objects;
import java.util.Optional;

/**
 * A POM from outside the repository that one of its POMs refers to: an external {@code <parent>}
 * such as {@code spring-boot-starter-parent}, or an imported BOM.
 *
 * @param declaredAt the line in this repository that sets its version
 */
public record PomReference(
        String groupId, String artifactId, String version, Optional<Location> declaredAt) {

    public PomReference {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(declaredAt, "declaredAt");
    }

    /** {@code groupId:artifactId}. */
    public String key() {
        return groupId + ":" + artifactId;
    }
}
