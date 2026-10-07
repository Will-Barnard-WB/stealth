package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.AnalyzerRunner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.IFactory;

class StealthCommandTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void execute_versionOption_printsVersion() {
        int exitCode = aCommandLine(Ansi.OFF).execute("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).startsWith("stealth ");
    }

    @Test
    void execute_helpOption_printsTitleCommandsOptionsAndHint() {
        int exitCode = aCommandLine(Ansi.OFF).execute("--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .startsWith("stealth dev")
                .contains(
                        "Find, prevent and clear tech debt and security debt.",
                        "Usage: stealth [-hV] [COMMAND]",
                        "Commands:",
                        "doctor",
                        "Options:",
                        "--version",
                        "Run 'stealth <command> --help' for more on a command.")
                .doesNotContain("\u001B[", "█", "Welcome");
        assertThat(out.toString().indexOf("Commands:"))
                .isLessThan(out.toString().indexOf("Options:"));
    }

    @Test
    void execute_noArguments_printsSameHelpAsHelpOption() {
        aCommandLine(Ansi.OFF).execute("--help");
        String help = out.toString();
        out.getBuffer().setLength(0);

        int exitCode = aCommandLine(Ansi.OFF).execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(help);
    }

    @Test
    void execute_ansiTerminal_printsLogoWelcomeBoxGettingStartedAndTip() {
        int exitCode = aCommandLine(Ansi.ON).execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains("\u001B[38;5;214m███████╗", "Welcome to", "Getting started:", "Tip: ")
                .contains("\u001B[1m\u001B[38;5;209mCommands:");
    }

    @Test
    void execute_unknownOption_printsErrorAndHelpHintWithoutUsage() {
        int exitCode = aCommandLine(Ansi.OFF).execute("--nope");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString())
                .contains("Unknown option: '--nope'", "Run 'stealth --help'")
                .doesNotContain("Usage:");
    }

    @Test
    void execute_misspelledCommand_suggestsClosestCommand() {
        int exitCode = aCommandLine(Ansi.OFF).execute("docter");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("Did you mean", "doctor");
    }

    @Test
    void execute_subcommandUnknownOption_pointsAtSubcommandHelp() {
        int exitCode = aCommandLine(Ansi.OFF).execute("doctor", "--nope");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("Run 'stealth doctor --help'");
    }

    private CommandLine aCommandLine(Ansi ansi) {
        IFactory factory =
                new IFactory() {
                    @Override
                    public <K> K create(Class<K> cls) throws Exception {
                        if (cls == DoctorCommand.class) {
                            return cls.cast(
                                    new DoctorCommand(
                                            new AnalyzerRunner(List.of(), Duration.ofSeconds(1)),
                                            new OfflineMode()));
                        }
                        if (cls == CleanCommand.class) {
                            AnalyzerRunner none =
                                    new AnalyzerRunner(List.of(), Duration.ofSeconds(1));
                            dev.stealth.core.maven.MavenModelLoader loader =
                                    new dev.stealth.core.maven.MavenModelLoader(
                                            dev.stealth.core.maven.MavenResolverSettings
                                                    .defaults());
                            dev.stealth.core.clean.PatchPlanner planner =
                                    new dev.stealth.core.clean.PatchPlanner(
                                            loader,
                                            new dev.stealth.core.vuln.VulnerabilityAnalyzer(
                                                    loader,
                                                    new dev.stealth.core.vuln.OsvClient(
                                                            new dev.stealth.core.http
                                                                    .CachedHttpClient(
                                                                    java.net.http.HttpClient
                                                                            .newHttpClient(),
                                                                    new dev.stealth.core.http
                                                                            .HttpCache(
                                                                            java.nio.file.Path.of(
                                                                                    System
                                                                                            .getProperty(
                                                                                                    "java.io.tmpdir")),
                                                                            Duration.ofHours(1),
                                                                            java.time.Clock
                                                                                    .systemUTC()),
                                                                    () -> true,
                                                                    1,
                                                                    Duration.ZERO),
                                                            dev.stealth.core.vuln.OsvClient.OSV)));
                            return cls.cast(
                                    new CleanCommand(
                                            none,
                                            planner,
                                            new dev.stealth.core.clean.Cleaner(
                                                    planner, java.time.Clock.systemUTC()),
                                            new dev.stealth.core.clean.CleanupVerifier(none),
                                            new OfflineMode(),
                                            new dev.stealth.core.impact.UpgradeImpact(loader)));
                        }
                        if (cls == McpCommand.class) {
                            return cls.cast(
                                    new McpCommand(
                                            new org.springframework.context.support
                                                    .GenericApplicationContext(),
                                            new StealthHome()));
                        }
                        if (cls == McpInstallCommand.class) {
                            return cls.cast(
                                    new McpInstallCommand(new ProcessRunner(), new StealthHome()));
                        }
                        if (cls == McpStatusCommand.class) {
                            return cls.cast(new McpStatusCommand(new StealthHome()));
                        }
                        return CommandLine.defaultFactory().create(cls);
                    }
                };
        CommandLine commandLine =
                StealthCli.configure(
                        new CommandLine(new StealthCommand(), factory),
                        ansi,
                        Banner.Glyphs.UNICODE);
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        return commandLine;
    }
}
