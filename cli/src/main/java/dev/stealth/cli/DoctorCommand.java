package dev.stealth.cli;

import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code stealth doctor [path]}: runs every analyzer and prints the fixes, most important first.
 */
@Component
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Check a repository for tech debt and security debt.")
public class DoctorCommand implements Callable<Integer> {

    private final AnalyzerRunner runner;
    private final OfflineMode offlineMode;

    @Spec private CommandSpec spec;

    @Parameters(
            arity = "0..1",
            defaultValue = ".",
            paramLabel = "PATH",
            description = "Repository to check. Defaults to the current directory.")
    private Path path;

    @Option(
            names = "--offline",
            description =
                    "Don't use the network: only cached lookups and the local Maven repository.")
    private boolean offline;

    @Option(
            names = "--all",
            description = "List every finding under its fix, and every fix, not just the top 10.")
    private boolean all;

    public DoctorCommand(AnalyzerRunner runner, OfflineMode offlineMode) {
        this.runner = runner;
        this.offlineMode = offlineMode;
    }

    @Override
    public Integer call() throws InterruptedException {
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            spec.commandLine().getErr().println("stealth doctor: not a directory: " + path);
            return ExitCode.USAGE;
        }

        offlineMode.set(offline);
        DoctorReport report = runner.run(new RepoContext(root, StealthConfig.defaults()));

        PrintWriter out = spec.commandLine().getOut();
        TerminalReport terminal =
                new TerminalReport(
                        spec.commandLine().getColorScheme().ansi(),
                        StealthCli.stdoutCanPrint(TerminalReport.UNICODE_SYMBOLS));
        out.print(terminal.render(root, report, all));
        out.flush();
        return ExitCode.OK;
    }
}
