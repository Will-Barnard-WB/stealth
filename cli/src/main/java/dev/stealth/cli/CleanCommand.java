package dev.stealth.cli;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.ConfigException;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfigLoader;
import dev.stealth.core.clean.CleanException;
import dev.stealth.core.clean.Cleaner;
import dev.stealth.core.clean.CleanupJson;
import dev.stealth.core.clean.CleanupPlan;
import dev.stealth.core.clean.CleanupResult;
import dev.stealth.core.clean.CleanupVerifier;
import dev.stealth.core.clean.PatchPlanner;
import dev.stealth.core.clean.TestRunner;
import dev.stealth.core.clean.Verification;
import dev.stealth.core.impact.UpgradeImpact;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code stealth clean [path]}: what can be fixed now, proven; {@code --apply} puts it on a new
 * branch, tested; {@code --verify} checks changes against a base commit.
 */
@Component
@Command(
        name = "clean",
        mixinStandardHelpOptions = true,
        abbreviateSynopsis = true,
        sortOptions = false,
        description = {
            "Fix known vulnerabilities without a framework upgrade, proven on the resolved"
                    + " dependency tree.",
            "",
            "Without options, shows the plan. --apply puts the safe patches on a new branch (your"
                    + " checkout is never touched), with the tests run before and after."
        })
public class CleanCommand implements Callable<Integer> {

    /** The analyzers the plan needs: vulnerabilities to patch, secrets to report. */
    private static final Set<String> ANALYZERS = Set.of("vuln", "secrets");

    private final AnalyzerRunner runner;
    private final PatchPlanner planner;
    private final Cleaner cleaner;
    private final CleanupVerifier verifier;
    private final OfflineMode offlineMode;
    private final UpgradeImpact upgradeImpact;

    @Spec private CommandSpec spec;

    @Parameters(
            arity = "0..1",
            defaultValue = ".",
            paramLabel = "PATH",
            description = "Repository to clean. Defaults to the current directory.")
    private Path path;

    @Option(names = "--apply", description = "Put the safe patches on a new branch.")
    private boolean apply;

    @Option(
            names = "--allow-minor",
            description =
                    "With --apply, also apply proven minor jumps past the versions your framework"
                            + " (e.g. Spring Boot) manages.")
    private boolean allowMinor;

    @Option(
            names = "--allow-major",
            description =
                    "With --apply, also apply proven patches that move to a new major version.")
    private boolean allowMajor;

    @Option(
            names = "--test-command",
            paramLabel = "CMD",
            description = "How to run the tests (default: ./mvnw -B test, or mvn).")
    private String testCommand;

    @Option(names = "--skip-tests", description = "With --apply, don't run the tests.")
    private boolean skipTests;

    @Option(
            names = "--allow-failing-tests",
            description = "Apply even when the tests already fail, requiring no new failures.")
    private boolean allowFailingTests;

    @Option(names = "--branch", paramLabel = "NAME", description = "Name of the branch to create.")
    private String branch;

    @Option(
            names = "--verify",
            description =
                    "Compare the working tree (or --base) with a commit: what's resolved and what's"
                            + " new.")
    private boolean verify;

    @Option(
            names = "--base",
            paramLabel = "REF",
            description = "With --verify, the commit or branch to compare with (default: HEAD).")
    private String base;

    @Option(names = "--tests", description = "With --verify, run the tests too.")
    private boolean tests;

    @Option(
            names = "--impact",
            paramLabel = "GROUP:ARTIFACT:VERSION",
            description =
                    "What upgrading a dependency to VERSION would break here: the code using APIs"
                            + " it removes or deprecates, line by line.")
    private String impact;

    @Option(names = "--json", description = "Print JSON.")
    private boolean json;

    @Option(
            names = "--offline",
            description = "Use only cached lookups and the local Maven repository.")
    private boolean offline;

    public CleanCommand(
            AnalyzerRunner runner,
            PatchPlanner planner,
            Cleaner cleaner,
            CleanupVerifier verifier,
            OfflineMode offlineMode,
            UpgradeImpact upgradeImpact) {
        this.runner = runner;
        this.planner = planner;
        this.cleaner = cleaner;
        this.verifier = verifier;
        this.offlineMode = offlineMode;
        this.upgradeImpact = upgradeImpact;
    }

    @Override
    public Integer call() throws InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            err.println("stealth clean: not a directory: " + path);
            return ExitCode.USAGE;
        }
        if ((apply ? 1 : 0) + (verify ? 1 : 0) + (impact != null ? 1 : 0) > 1) {
            err.println("stealth clean: use one of --apply, --verify or --impact");
            return ExitCode.USAGE;
        }
        if (apply && verify) {
            err.println("stealth clean: use --apply or --verify, not both");
            return ExitCode.USAGE;
        }
        offlineMode.set(offline);
        CleanReport text = new CleanReport(spec.commandLine().getColorScheme().ansi());
        try {
            RepoContext context = new RepoContext(root, loadConfig(root).config());
            if (impact != null) {
                String[] coordinates = impact.split(":");
                if (coordinates.length != 3) {
                    err.println(
                            "stealth clean: --impact takes group:artifact:version, e.g."
                                    + " org.springframework:spring-web:6.1.14");
                    return ExitCode.USAGE;
                }
                UpgradeImpact.Result result =
                        upgradeImpact.analyze(
                                context, coordinates[0], coordinates[1], coordinates[2]);
                out.print(
                        json
                                ? CleanupJson.impact(result) + System.lineSeparator()
                                : text.impact(root, result));
                out.flush();
                return ExitCode.OK;
            }
            if (verify) {
                Verification verification =
                        verifier.verify(
                                context,
                                Optional.ofNullable(base),
                                tests ? Optional.of(testRunner()) : Optional.empty());
                out.print(
                        json
                                ? CleanupJson.verification(verification) + System.lineSeparator()
                                : text.verification(root, verification));
                out.flush();
                return verification.green() ? ExitCode.OK : 1;
            }

            DoctorReport report =
                    runner.run(context, analyzer -> ANALYZERS.contains(analyzer.id()));
            CleanupPlan plan = planner.plan(context, report);
            if (!apply) {
                out.print(
                        json
                                ? CleanupJson.plan(plan) + System.lineSeparator()
                                : text.plan(root, plan));
                out.flush();
                return ExitCode.OK;
            }
            CleanupResult result =
                    cleaner.apply(
                            context,
                            plan,
                            new Cleaner.Options(
                                    allowMajor,
                                    allowMinor,
                                    skipTests ? Optional.empty() : Optional.of(testRunner()),
                                    allowFailingTests,
                                    Optional.ofNullable(branch)));
            out.print(
                    json
                            ? CleanupJson.result(result) + System.lineSeparator()
                            : text.result(root, result));
            out.flush();
            return result.failed().isEmpty() ? ExitCode.OK : 1;
        } catch (CleanException | ConfigException | IOException | IllegalArgumentException e) {
            err.println("stealth clean: " + e.getMessage());
            err.flush();
            return ExitCode.USAGE;
        }
    }

    private TestRunner testRunner() {
        Optional<List<String>> command =
                testCommand == null || testCommand.isBlank()
                        ? Optional.empty()
                        : Optional.of(Arrays.asList(testCommand.strip().split("\\s+")));
        return new TestRunner(command, TestRunner.DEFAULT_TIMEOUT);
    }

    private StealthConfigLoader.Loaded loadConfig(Path root) throws ConfigException {
        Set<String> rules = new HashSet<>();
        Set<String> analyzers = new HashSet<>();
        for (Analyzer analyzer : runner.analyzers()) {
            analyzers.add(analyzer.id());
            analyzer.rules().forEach(rule -> rules.add(rule.id()));
        }
        return StealthConfigLoader.load(root, rules, analyzers);
    }
}
