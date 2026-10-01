package dev.stealth.cli;

import dev.stealth.mcp.McpServer;
import dev.stealth.mcp.McpToken;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code stealth mcp status}: whether the server is running. Exit code 1 when it isn't. */
@Component
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Say whether the stealth MCP server is running.")
public class McpStatusCommand implements Callable<Integer> {

    private final StealthHome home;

    @Spec private CommandSpec spec;

    @Option(
            names = "--port",
            paramLabel = "PORT",
            description = "Port to check. Default: ${DEFAULT-VALUE}.")
    private int port = McpServer.DEFAULT_PORT;

    public McpStatusCommand(StealthHome home) {
        this.home = home;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Optional<String> version = McpServer.runningVersion(port);
        boolean hasToken = Files.isRegularFile(home.path().resolve(McpToken.FILE_NAME));
        if (version.isPresent()) {
            out.println(
                    "stealth MCP server "
                            + version.get()
                            + " is running at "
                            + McpServer.url(port));
        } else {
            out.println(
                    "No stealth MCP server on port "
                            + port
                            + ". Start it with: stealth mcp"
                            + (port == McpServer.DEFAULT_PORT ? "" : " --port " + port));
        }
        if (!hasToken) {
            out.println("Not set up yet: run stealth mcp install to register it with Claude Code.");
        }
        out.flush();
        return version.isPresent() ? 0 : 1;
    }
}
