package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.AnalyzerRunner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.IFactory;

class StealthCommandTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        IFactory factory =
                new IFactory() {
                    @Override
                    public <K> K create(Class<K> cls) throws Exception {
                        if (cls == DoctorCommand.class) {
                            return cls.cast(
                                    new DoctorCommand(
                                            new AnalyzerRunner(List.of(), Duration.ofSeconds(1))));
                        }
                        return CommandLine.defaultFactory().create(cls);
                    }
                };
        commandLine = new CommandLine(new StealthCommand(), factory);
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF));
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
        assertThat(out.toString()).contains("Usage: stealth", "--version", "doctor");
    }

    @Test
    void execute_unknownOption_printsUsageToStderrAndFails() {
        int exitCode = commandLine.execute("--nope");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("Unknown option: '--nope'", "Usage: stealth");
    }
}
