package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.mcp.McpToken;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import picocli.CommandLine;

class McpCommandTest {

    @TempDir private Path home;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void install_withClaudeOnPath_replacesTheRegistrationWithTheToken() throws Exception {
        FakeProcesses processes =
                new FakeProcesses(Optional.of(Path.of("/usr/local/bin/claude")), 0);

        int exitCode = execute(new McpInstallCommand(processes, home()));

        String token = McpToken.loadOrCreate(home);
        assertThat(exitCode).isZero();
        assertThat(processes.calls)
                .containsExactly(
                        List.of("mcp", "remove", "--scope", "user", "stealth"),
                        List.of(
                                "mcp",
                                "add",
                                "--transport",
                                "http",
                                "--scope",
                                "user",
                                "stealth",
                                "http://127.0.0.1:7331/mcp",
                                "--header",
                                "Authorization: Bearer " + token));
        assertThat(out.toString())
                .contains("Registered with Claude Code for every repository")
                .contains("\"Authorization\": \"Bearer " + token + "\"")
                .contains("Then start the server and keep it running: stealth mcp");
    }

    @Test
    void install_withoutClaude_printsTheCommandToRun() throws Exception {
        FakeProcesses processes = new FakeProcesses(Optional.empty(), 0);

        int exitCode = execute(new McpInstallCommand(processes, home()), "--port", "7400");

        assertThat(exitCode).isZero();
        assertThat(processes.calls).isEmpty();
        assertThat(out.toString())
                .contains("claude command isn't on PATH")
                .contains(
                        "claude mcp add --transport http --scope user stealth"
                                + " http://127.0.0.1:7400/mcp --header \"Authorization: Bearer ")
                .contains("stealth mcp --port 7400");
    }

    @Test
    void install_claudeFails_exitsOneWithItsOutput() {
        FakeProcesses processes = new FakeProcesses(Optional.of(Path.of("claude")), 1);

        int exitCode = execute(new McpInstallCommand(processes, home()));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("claude mcp add failed", "boom");
    }

    @Test
    void install_rotateToken_registersANewToken() throws Exception {
        String old = McpToken.loadOrCreate(home);
        FakeProcesses processes = new FakeProcesses(Optional.of(Path.of("claude")), 0);

        execute(new McpInstallCommand(processes, home()), "--rotate-token");

        assertThat(McpToken.loadOrCreate(home)).isNotEqualTo(old);
        assertThat(processes.calls.get(1).getLast())
                .isEqualTo("Authorization: Bearer " + McpToken.loadOrCreate(home));
        assertThat(out.toString()).contains("restart it if it's running");
    }

    @Test
    void mcp_portInUse_exitsOneSayingSo() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            GenericApplicationContext context = new GenericApplicationContext();
            int exitCode =
                    execute(
                            new McpCommand(context, home()),
                            "--port",
                            String.valueOf(taken.getLocalPort()));

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString())
                    .contains("port " + taken.getLocalPort() + " is in use by another program");
            assertThat(Files.exists(home.resolve(McpToken.FILE_NAME))).isFalse();
        }
    }

    @Test
    void status_nothingRunning_exitsOneWithHowToStart() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }

        int exitCode = execute(new McpStatusCommand(home()), "--port", String.valueOf(port));

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString())
                .contains(
                        "No stealth MCP server on port "
                                + port
                                + ". Start it with: stealth mcp --port "
                                + port)
                .contains("Not set up yet: run stealth mcp install");
    }

    private StealthHome home() {
        return new StealthHome() {
            @Override
            public Path path() {
                return home;
            }
        };
    }

    private int execute(Object command, String... args) {
        CommandLine.IFactory factory =
                new CommandLine.IFactory() {
                    @Override
                    public <K> K create(Class<K> cls) throws Exception {
                        if (cls == McpInstallCommand.class) {
                            return cls.cast(
                                    new McpInstallCommand(
                                            new FakeProcesses(Optional.empty(), 0), home()));
                        }
                        if (cls == McpStatusCommand.class) {
                            return cls.cast(new McpStatusCommand(home()));
                        }
                        return CommandLine.defaultFactory().create(cls);
                    }
                };
        CommandLine commandLine = new CommandLine(command, factory);
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        return commandLine.execute(args);
    }

    private static final class FakeProcesses extends ProcessRunner {

        private final Optional<Path> claude;
        private final int addExitCode;
        final List<List<String>> calls = new ArrayList<>();

        FakeProcesses(Optional<Path> claude, int addExitCode) {
            this.claude = claude;
            this.addExitCode = addExitCode;
        }

        @Override
        public Optional<Path> find(String program) {
            return claude;
        }

        @Override
        public Result run(Path program, List<String> arguments) {
            calls.add(arguments);
            return arguments.get(1).equals("add")
                    ? new Result(addExitCode, addExitCode == 0 ? "Added" : "boom")
                    : new Result(0, "Removed");
        }
    }
}
