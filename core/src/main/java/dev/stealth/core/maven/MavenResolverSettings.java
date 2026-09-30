package dev.stealth.core.maven;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where {@link MavenModelLoader} finds parent POMs, BOMs and dependency descriptors.
 *
 * @param localRepository searched first, and where downloads are stored
 * @param remoteRepository Maven Central, or a mirror of it
 * @param offline use only {@code localRepository}; anything missing becomes a warning
 */
public record MavenResolverSettings(Path localRepository, URI remoteRepository, boolean offline) {

    public static final URI MAVEN_CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");

    public MavenResolverSettings {
        Objects.requireNonNull(localRepository, "localRepository");
        Objects.requireNonNull(remoteRepository, "remoteRepository");
    }

    /** {@code ~/.m2/repository} and Maven Central, online. */
    public static MavenResolverSettings defaults() {
        return new MavenResolverSettings(
                Path.of(System.getProperty("user.home"), ".m2", "repository"),
                MAVEN_CENTRAL,
                false);
    }

    public MavenResolverSettings withOffline(boolean offline) {
        return new MavenResolverSettings(localRepository, remoteRepository, offline);
    }
}
