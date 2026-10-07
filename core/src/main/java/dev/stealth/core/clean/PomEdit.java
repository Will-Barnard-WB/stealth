package dev.stealth.core.clean;

import java.util.Objects;

/**
 * One change to one POM in the repository. Applied as a text edit by {@link PomEditor}, so the rest
 * of the file, including formatting and comments, is unchanged.
 */
public sealed interface PomEdit {

    /** Repo-relative path of the POM. */
    String pomPath();

    /** The version this edit sets. */
    String value();

    /** Edits with the same key change the same thing, so they're merged into one. */
    String key();

    /** This edit, setting {@code value} instead. */
    PomEdit withValue(String value);

    /** One line describing the change, for people and agents. */
    String describe();

    /**
     * Replace {@code from} with {@code value} on a line that sets a version: an element or
     * property.
     */
    record SetVersion(String pomPath, int line, String from, String value) implements PomEdit {

        public SetVersion {
            Objects.requireNonNull(pomPath, "pomPath");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String key() {
            return pomPath + ":" + line;
        }

        @Override
        public PomEdit withValue(String value) {
            return new SetVersion(pomPath, line, from, value);
        }

        @Override
        public String describe() {
            return "Change " + from + " to " + value + " at " + pomPath + ":" + line;
        }
    }

    /**
     * Set a property in the POM's {@code <properties>}, adding it if it isn't there: overrides a
     * version the parent manages through that property.
     */
    record SetProperty(String pomPath, String name, String value) implements PomEdit {

        public SetProperty {
            Objects.requireNonNull(pomPath, "pomPath");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String key() {
            return pomPath + "#" + name;
        }

        @Override
        public PomEdit withValue(String value) {
            return new SetProperty(pomPath, name, value);
        }

        @Override
        public String describe() {
            return "Set <" + name + ">" + value + "</" + name + "> in " + pomPath;
        }
    }

    /** Pin a version in the POM's {@code <dependencyManagement>}, adding the entry. */
    record PinVersion(String pomPath, String groupId, String artifactId, String value)
            implements PomEdit {

        public PinVersion {
            Objects.requireNonNull(pomPath, "pomPath");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String key() {
            return pomPath + "@" + groupId + ":" + artifactId;
        }

        @Override
        public PomEdit withValue(String value) {
            return new PinVersion(pomPath, groupId, artifactId, value);
        }

        @Override
        public String describe() {
            return "Pin "
                    + groupId
                    + ":"
                    + artifactId
                    + " to "
                    + value
                    + " in "
                    + pomPath
                    + " <dependencyManagement>";
        }
    }
}
