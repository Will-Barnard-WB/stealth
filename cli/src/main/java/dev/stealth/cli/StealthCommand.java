package dev.stealth.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** The root {@code stealth} command. Layout and colours come from {@link StealthCli}. */
@Component
@Command(
        name = "stealth",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        subcommands = {DoctorCommand.class, CleanCommand.class, McpCommand.class},
        description = "Find, prevent and clear tech debt and security debt.")
public class StealthCommand implements Runnable {

    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
