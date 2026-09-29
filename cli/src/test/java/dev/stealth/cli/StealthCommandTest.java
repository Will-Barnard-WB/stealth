package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class StealthCommandTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        commandLine = new CommandLine(new StealthCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
    }

    @Test
    void execute_versionOption_printsVersion() {
        int exitCode = commandLine.execute("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).startsWith("stealth ");
    }

    @Test
    void execute_helpOption_printsUsage() {
        int exitCode = commandLine.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Usage: stealth", "--version");
    }

    @Test
    void execute_unknownOption_printsUsageToStderrAndFails() {
        int exitCode = commandLine.execute("--nope");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("Unknown option: '--nope'", "Usage: stealth");
    }
}
