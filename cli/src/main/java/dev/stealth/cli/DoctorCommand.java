package dev.stealth.cli;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code stealth doctor [path]}. Prints a plain summary until the renderers ticket lands. */
@Component
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Check a repository for tech debt and security debt.")
public class DoctorCommand implements Callable<Integer> {

    private final AnalyzerRunner runner;

    @Spec private CommandSpec spec;

    @Parameters(
            arity = "0..1",
            defaultValue = ".",
            paramLabel = "PATH",
            description = "Repository to check. Defaults to the current directory.")
    private Path path;

    public DoctorCommand(AnalyzerRunner runner) {
        this.runner = runner;
    }

    @Override
    public Integer call() throws InterruptedException {
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            spec.commandLine().getErr().println("stealth doctor: not a directory: " + path);
            return ExitCode.USAGE;
        }

        DoctorReport report = runner.run(new RepoContext(root, StealthConfig.defaults()));

        PrintWriter out = spec.commandLine().getOut();
        out.printf("stealth doctor %s%n%n", root);
        for (AnalyzerResult result : report.results()) {
            out.printf(
                    "  %-14s %-9s %-10s %6d ms%s%n",
                    result.analyzerId(),
                    lowerCase(result.category()),
                    lowerCase(result.status()).replace('_', ' '),
                    result.duration().toMillis(),
                    result.error().map(error -> "  " + error).orElse(""));
        }
        if (!report.results().isEmpty()) {
            out.println();
        }
        for (Finding finding : report.findings()) {
            out.printf("  %-8s %s  %s%n", finding.severity(), finding.ruleId(), finding.message());
        }
        out.printf(
                "%d %s from %d %s%n",
                report.findings().size(),
                report.findings().size() == 1 ? "finding" : "findings",
                report.results().size(),
                report.results().size() == 1 ? "analyzer" : "analyzers");
        out.flush();
        return ExitCode.OK;
    }

    private static String lowerCase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
