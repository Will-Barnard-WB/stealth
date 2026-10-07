package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.util.Objects;
import java.util.Optional;

/**
 * A version from a module's effective {@code <dependencyManagement>}, and how it could be changed.
 *
 * @param version the managed version
 * @param managedBy the model that manages it, e.g. {@code
 *     org.springframework.boot:spring-boot-dependencies:2.7.18}
 * @param property the property that model writes the version with, e.g. {@code tomcat.version}
 * @param overridable whether setting {@code property} in this repository's POM changes the version:
 *     true when the managing model is a parent (inherited), false when it's an imported BOM, whose
 *     properties Maven interpolates in the BOM's own context
 * @param declaredAt the line in this repository that sets it, when it's managed here (directly, or
 *     by setting the version of the BOM that manages it)
 * @param bom the BOM that manages it, {@code groupId:artifactId}, when that BOM is imported by this
 *     repository or one of its parents; changing {@code property} or {@code declaredAt} then moves
 *     the whole BOM, so related artifacts stay on matching versions
 */
public record ManagedVersion(
        String version,
        String managedBy,
        Optional<String> property,
        boolean overridable,
        Optional<Location> declaredAt,
        Optional<String> bom) {

    public ManagedVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(managedBy, "managedBy");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(declaredAt, "declaredAt");
        Objects.requireNonNull(bom, "bom");
    }
}
