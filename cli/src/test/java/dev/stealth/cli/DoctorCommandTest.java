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
    void execute_analyzersSucceedAndFail_printsSummaryAndExitsZero() {
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
                .contains(repo.toAbsolutePath().toString())
                .containsPattern("hygiene +tech +ok")
                .containsPattern("vuln +security +failed .*OSV.dev unreachable")
                .contains("LOW      hygiene/missing-codeowners  No CODEOWNERS file")
                .contains("1 finding from 2 analyzers");
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
