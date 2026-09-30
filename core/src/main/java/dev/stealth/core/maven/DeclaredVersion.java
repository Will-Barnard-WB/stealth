package dev.stealth.core.maven;

import dev.stealth.core.Location;
import java.util.Objects;
import java.util.Optional;

/**
 * A version such as the Java release or the Spring Boot version, and the line in this repository
 * that sets it.
 */
public record DeclaredVersion(String value, Optional<Location> declaredAt) {

    public DeclaredVersion {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(declaredAt, "declaredAt");
    }
}
