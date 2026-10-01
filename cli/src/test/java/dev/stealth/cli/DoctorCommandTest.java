package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DoctorCommandTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private final OfflineMode offlineMode = new OfflineMode();

    @TempDir private Path repo;

    @Test
    void execute_analyzersSucceedAndFail_printsTheReportAndExitsZero() {
        Analyzer hygiene = analyzer("hygiene", Category.TECH, List.of(missingCodeowners()));
        Analyzer vuln =
                new Analyzer() {
                    @Override
                    public String id() {
                        return "vuln";
                    }

                    @Override
                    public Category category() {
                        return Category.SECURITY;
                    }

                    @Override
                    public List<Finding> analyze(RepoContext context) {
                        throw new IllegalStateException("OSV.dev unreachable");
                    }
                };

        int exitCode = execute(List.of(hygiene, vuln), repo.toString());

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains(repo.getFileName().toString())
                .contains(
                        "hygiene",
                        "vuln failed: java.lang.IllegalStateException: OSV.dev unreachable")
                .contains("Health     100 A   incomplete: an analyzer failed")
                .contains("Security   100 A   incomplete: an analyzer failed")
                .contains("Tech       99  A   1 low")
                .contains("1  (repository)   No CODEOWNERS file")
                .contains("1 finding in 1 fix");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void execute_missingDirectory_printsErrorAndFailsWithUsage() {
        int exitCode = execute(List.of(), repo.resolve("nope").toString());

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("not a directory");
    }

    @Test
    void execute_offlineOption_switchesTheRunToOffline() {
        int exitCode = execute(List.of(), "--offline", repo.toString());

        assertThat(exitCode).isZero();
        assertThat(offlineMode.isOffline()).isTrue();
    }

    @Test
    void execute_analyzerFlag_runsOnlyThatAnalyzer() {
        Analyzer secrets = analyzer("secrets", Category.SECURITY, List.of());
        Analyzer vuln = analyzer("vuln", Category.SECURITY, List.of());
        Analyzer duplication = analyzer("duplication", Category.TECH, List.of());

        execute(List.of(secrets, vuln, duplication), "--secrets", repo.toString());

        assertThat(out.toString()).contains("secrets").doesNotContain("vuln", "duplication");
    }

    @Test
    void execute_hygieneFlag_runsOnlyTheHygieneAnalyzer() {
        Analyzer hygiene = analyzer("hygiene", Category.TECH, List.of());
        Analyzer duplication = analyzer("duplication", Category.TECH, List.of());

        execute(List.of(hygiene, duplication), "--hygiene", repo.toString());

        assertThat(out.toString()).contains("hygiene").doesNotContain("duplication");
    }

    @Test
    void execute_analyzerAndCategoryFlags_runEverythingTheyName() {
        Analyzer secrets = analyzer("secrets", Category.SECURITY, List.of());
        Analyzer vuln = analyzer("vuln", Category.SECURITY, List.of());
        Analyzer deps = analyzer("deps", Category.TECH, List.of());
        Analyzer duplication = analyzer("duplication", Category.TECH, List.of());

        execute(
                List.of(secrets, vuln, deps, duplication),
                "--security",
                "--duplication",
                repo.toString());

        assertThat(out.toString())
                .contains("secrets", "vuln", "duplication")
                .doesNotContain("deps");
    }

    @Test
    void execute_severityFlags_showOnlyThoseSeverities() {
        Analyzer hygiene =
                analyzer(
                        "hygiene",
                        Category.TECH,
                        List.of(
                                finding(Severity.CRITICAL, "Critical problem"),
                                finding(Severity.HIGH, "High problem"),
                                finding(Severity.LOW, "Low problem")));

        execute(List.of(hygiene), "--critical", "--high", repo.toString());

        assertThat(out.toString())
                .contains("1 critical", "1 high", "2 findings")
                .doesNotContain("Low problem", "1 low");
    }

    @Test
    void execute_severityFlagsMatchNothing_saysNoneOfThoseSeverities() {
        Analyzer hygiene = analyzer("hygiene", Category.TECH, List.of(missingCodeowners()));

        execute(List.of(hygiene), "--critical", "--high", repo.toString());

        assertThat(out.toString()).contains("No critical or high findings.");
    }

    @Test
    void usage_groupsTheFiltersAndKeepsTheSynopsisShort() {
        String usage =
                new CommandLine(
                                new DoctorCommand(
                                        new AnalyzerRunner(List.of(), Duration.ofSeconds(10)),
                                        offlineMode))
                        .getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage)
                .contains("Usage: doctor [OPTIONS] [PATH]")
                .contains("Run only:", "--secrets", "--security")
                .contains("Show only:", "--critical", "--info");
    }

    @Test
    void execute_failUnderInStealthYml_exitsOneBelowTheThreshold() throws Exception {
        Files.writeString(repo.resolve(".stealth.yml"), "version: 1\nfail-under:\n  tech: 100\n");
        Analyzer hygiene = analyzer("hygiene", Category.TECH, List.of(missingCodeowners()));

        int exitCode = execute(List.of(hygiene), repo.toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("fail-under: tech score 99 is below 100");
    }

    @Test
    void execute_failUnderOption_overridesTheOverallThresholdInStealthYml() throws Exception {
        Files.writeString(repo.resolve(".stealth.yml"), "version: 1\nfail-under: 100\n");
        // One high tech finding: tech 92, overall 0.6 × 100 + 0.4 × 92 = 96.8, so 97
        Analyzer hygiene =
                analyzer("hygiene", Category.TECH, List.of(finding(Severity.HIGH, "High problem")));
        Analyzer vuln = analyzer("vuln", Category.SECURITY, List.of());

        assertThat(execute(List.of(hygiene, vuln), repo.toString())).isEqualTo(1);
        assertThat(execute(List.of(hygiene, vuln), "--fail-under", "90", repo.toString())).isZero();
    }

    @Test
    void execute_invalidStealthYml_exitsTwoNamingTheLine() throws Exception {
        Files.writeString(repo.resolve(".stealth.yml"), "version: 1\nfail-under: 101\n");

        int exitCode = execute(List.of(), repo.toString());

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString())
                .contains(
                        "stealth doctor: .stealth.yml:2: fail-under must be between 0 and 100, got"
                                + " 101");
    }

    @Test
    void execute_configOption_readsThatFileAndShowsItsWarnings() throws Exception {
        Path config = repo.resolve("ci-stealth.yml");
        Files.writeString(
                config, "version: 1\nignroe: []\nseverity:\n  hygiene/missing-codeowners: off\n");
        Analyzer hygiene = analyzer("hygiene", Category.TECH, List.of(missingCodeowners()));

        execute(List.of(hygiene), "--config", config.toString(), repo.toString());

        assertThat(out.toString())
                .contains(
                        "warning  " + config + ":2: unknown key 'ignroe' (did you mean 'ignore'?)")
                .contains("No problems found.");
    }

    private int execute(List<Analyzer> analyzers, String... args) {
        CommandLine commandLine =
                new CommandLine(
                        new DoctorCommand(
                                new AnalyzerRunner(analyzers, Duration.ofSeconds(10)),
                                offlineMode));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        return commandLine.execute(args);
    }

    private static Finding missingCodeowners() {
        return new Finding(
                "hygiene/missing-codeowners",
                Category.TECH,
                Severity.LOW,
                "No CODEOWNERS file",
                Location.repository(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of("hygiene/missing-codeowners", "CODEOWNERS"));
    }

    private static Finding finding(Severity severity, String message) {
        return new Finding(
                "hygiene/" + severity.name().toLowerCase(),
                Category.TECH,
                severity,
                message,
                Location.repository(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of("hygiene/" + severity.name().toLowerCase(), message));
    }

    private static Analyzer analyzer(String id, Category category, List<Finding> findings) {
        return new Analyzer() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Category category() {
                return category;
            }

            @Override
            public List<Finding> analyze(RepoContext context) {
                return findings;
            }
        };
    }
}
