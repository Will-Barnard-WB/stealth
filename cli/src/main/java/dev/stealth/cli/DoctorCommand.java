package dev.stealth.cli;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.Category;
import dev.stealth.core.ConfigException;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.StealthConfigLoader;
import dev.stealth.core.score.FailUnderGate;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code stealth doctor [path]}: runs every analyzer and prints the fixes, most important first.
 * The {@code Run only} flags narrow which analyzers run, the {@code Show only} flags which
 * severities are shown.
 */
@Component
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        abbreviateSynopsis = true,
        sortOptions = false,
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
            names = "--config",
            paramLabel = "FILE",
            description = "Read this file instead of the repository's .stealth.yml.")
    private Path configFile;

    @Option(
            names = "--fail-under",
            paramLabel = "N",
            description =
                    "Exit with code 1 if the overall score is below N (0-100). Overrides"
                            + " fail-under in .stealth.yml.")
    private Integer failUnder;

    @Option(
            names = "--all",
            description = "List every finding under its fix, and every fix, not just the top 10.")
    private boolean all;

    @ArgGroup(exclusive = false, heading = "%n@|bold," + Banner.ACCENT + " Run only:|@%n")
    private RunOnly runOnly = new RunOnly();

    @ArgGroup(exclusive = false, heading = "%n@|bold," + Banner.ACCENT + " Show only:|@%n")
    private ShowOnly showOnly = new ShowOnly();

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

        PrintWriter err = spec.commandLine().getErr();
        if (failUnder != null && (failUnder < 0 || failUnder > 100)) {
            err.println("stealth doctor: --fail-under must be between 0 and 100, got " + failUnder);
            return ExitCode.USAGE;
        }
        StealthConfigLoader.Loaded loaded;
        try {
            loaded = loadConfig(root);
        } catch (ConfigException e) {
            err.println("stealth doctor: " + e.getMessage());
            return ExitCode.USAGE;
        }
        StealthConfig config = loaded.config();

        offlineMode.set(offline);
        DoctorReport report =
                runner.run(new RepoContext(root, config), runOnly.selection())
                        .withWarnings(loaded.warnings());

        // --fail-under replaces the overall threshold; .stealth.yml's category thresholds stay
        StealthConfig.FailUnder thresholds =
                failUnder != null ? config.failUnder().withOverall(failUnder) : config.failUnder();
        Optional<FailUnderGate.Result> gate =
                thresholds.isSet()
                        ? Optional.of(FailUnderGate.check(report.score(), thresholds))
                        : Optional.empty();
        // The renderer narrows the listing to these; the score still counts every finding
        Set<Severity> severities = showOnly.severities();

        PrintWriter out = spec.commandLine().getOut();
        TerminalReport terminal =
                new TerminalReport(
                        spec.commandLine().getColorScheme().ansi(),
                        StealthCli.stdoutCanPrint(TerminalReport.UNICODE_SYMBOLS));
        out.print(terminal.render(root, report, all, severities, gate));
        out.flush();
        return gate.map(FailUnderGate.Result::exitCode).orElse(ExitCode.OK);
    }

    private StealthConfigLoader.Loaded loadConfig(Path root) throws ConfigException {
        Set<String> rules = new HashSet<>();
        Set<String> analyzers = new HashSet<>();
        for (Analyzer analyzer : runner.analyzers()) {
            analyzers.add(analyzer.id());
            analyzer.rules().forEach(rule -> rules.add(rule.id()));
        }
        if (configFile != null) {
            return StealthConfigLoader.loadFile(
                    configFile, configFile.toString(), rules, analyzers);
        }
        return StealthConfigLoader.load(root, rules, analyzers);
    }

    /** Which analyzers to run; none set runs them all. Each flag adds to the others. */
    static class RunOnly {

        @Option(names = "--security", description = "Security analyzers")
        boolean security;

        @Option(names = "--tech", description = "Tech analyzers")
        boolean tech;

        @Option(names = "--deps", description = "Outdated dependencies")
        boolean deps;

        @Option(names = "--vuln", description = "Known vulnerabilities")
        boolean vuln;

        @Option(names = "--eol", description = "End-of-life frameworks and runtimes")
        boolean eol;

        @Option(names = "--secrets", description = "Leaked secrets")
        boolean secrets;

        @Option(names = "--maintenance", description = "Unmaintained dependencies")
        boolean maintenance;

        @Option(names = "--duplication", description = "Duplicated code")
        boolean duplication;

        @Option(names = "--hygiene", description = "Repo hygiene: CODEOWNERS, CI, tests, branches")
        boolean hygiene;

        Predicate<Analyzer> selection() {
            Set<Category> categories = EnumSet.noneOf(Category.class);
            if (security) {
                categories.add(Category.SECURITY);
            }
            if (tech) {
                categories.add(Category.TECH);
            }
            Set<String> ids = new HashSet<>();
            addIf(ids, deps, "deps");
            addIf(ids, vuln, "vuln");
            addIf(ids, eol, "eol");
            addIf(ids, secrets, "secrets");
            addIf(ids, maintenance, "maintenance");
            addIf(ids, duplication, "duplication");
            addIf(ids, hygiene, "hygiene");
            if (categories.isEmpty() && ids.isEmpty()) {
                return analyzer -> true;
            }
            return analyzer ->
                    ids.contains(analyzer.id()) || categories.contains(analyzer.category());
        }

        private static void addIf(Set<String> ids, boolean selected, String id) {
            if (selected) {
                ids.add(id);
            }
        }
    }

    /** Which severities to show; none set shows them all. Each flag adds to the others. */
    static class ShowOnly {

        @Option(names = "--critical", description = "Critical findings")
        boolean critical;

        @Option(names = "--high", description = "High findings")
        boolean high;

        @Option(names = "--medium", description = "Medium findings")
        boolean medium;

        @Option(names = "--low", description = "Low findings")
        boolean low;

        @Option(names = "--info", description = "Info findings")
        boolean info;

        Set<Severity> severities() {
            Set<Severity> severities = EnumSet.noneOf(Severity.class);
            if (critical) {
                severities.add(Severity.CRITICAL);
            }
            if (high) {
                severities.add(Severity.HIGH);
            }
            if (medium) {
                severities.add(Severity.MEDIUM);
            }
            if (low) {
                severities.add(Severity.LOW);
            }
            if (info) {
                severities.add(Severity.INFO);
            }
            return severities;
        }
    }
}
