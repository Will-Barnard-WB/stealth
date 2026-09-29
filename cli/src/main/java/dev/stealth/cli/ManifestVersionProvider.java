package dev.stealth.cli;

import java.util.Objects;
import picocli.CommandLine.IVersionProvider;

/** Reads the version from the jar manifest, which Maven writes at package time. */
class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {"stealth " + version()};
    }

    /** The release version, or {@code dev} when running from classes rather than the jar. */
    static String version() {
        return Objects.requireNonNullElse(
                StealthCommand.class.getPackage().getImplementationVersion(), "dev");
    }
}
