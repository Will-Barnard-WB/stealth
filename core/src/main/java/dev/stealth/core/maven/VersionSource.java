package dev.stealth.core.maven;

/** Where a dependency's version comes from, which decides what a fix has to change. */
public enum VersionSource {
    /** A {@code <version>} on the dependency itself, possibly through a property. */
    DECLARED,
    /** A {@code <dependencyManagement>} entry in one of this repository's POMs. */
    MANAGED,
    /** A BOM imported by one of this repository's POMs. */
    BOM,
    /** A parent POM from outside the repository, such as {@code spring-boot-starter-parent}. */
    PARENT,
    /** Another module of this repository. */
    INTERNAL,
    /** Couldn't be traced to a line in this repository. */
    UNKNOWN
}
