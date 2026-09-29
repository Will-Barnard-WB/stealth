package dev.stealth.cli;

import java.util.Objects;
import picocli.CommandLine.IVersionProvider;

/** Reads the version from the jar manifest, which Maven writes at package time. */
class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version =
                Objects.requireNonNullElse(
                        StealthCommand.class.getPackage().getImplementationVersion(), "dev");
        return new String[] {"stealth " + version};
    }
}
