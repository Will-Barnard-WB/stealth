package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.RepoContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/** Runs {@code stealth doctor} through the Spring context, so analyzer beans get picked up. */
@SpringBootTest
class DoctorCommandIT {

    @Autowired private IFactory factory;

    @Autowired private StealthCommand command;

    @TempDir private Path repo;

    @Test
    void execute_doctorOnRepoWithoutPom_runsEveryAnalyzerBeanAndExitsZero() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(command, factory);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("doctor", repo.toString());

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains("stub", "deps", "vuln", "eol", "secrets")
                .doesNotContain("failed")
                .contains("No problems found.");
    }

    @TestConfiguration
    static class StubAnalyzerConfiguration {

        @Bean
        Analyzer stubAnalyzer() {
            return new Analyzer() {
                @Override
                public String id() {
                    return "stub";
                }

                @Override
                public Category category() {
                    return Category.TECH;
                }

                @Override
                public List<Finding> analyze(RepoContext context) {
                    return List.of();
                }
            };
        }
    }
}
