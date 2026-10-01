package dev.stealth.cli;

import dev.stealth.mcp.McpServer;
import dev.stealth.mcp.McpToken;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code stealth mcp install}: registers the server with Claude Code for every repository (user
 * scope), with the token it needs, and prints the configuration for other clients.
 */
@Component
@Command(
        name = "install",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = {
            "Register the stealth MCP server with Claude Code (all repositories), and print the"
                    + " config for Cursor and other MCP clients."
        })
public class McpInstallCommand implements Callable<Integer> {

    static final String SERVER_NAME = "stealth";

    private final ProcessRunner processes;
    private final StealthHome home;

    @Spec private CommandSpec spec;

    @Option(
            names = "--port",
            paramLabel = "PORT",
            description = "Port the server runs on. Default: ${DEFAULT-VALUE}.")
    private int port = McpServer.DEFAULT_PORT;

    @Option(
            names = "--rotate-token",
            description =
                    "Make a new token. Clients registered with the old one stop working; restart"
                            + " a running server.")
    private boolean rotateToken;

    @Option(
            names = "--print-only",
            description = "Print the commands and config instead of running claude.")
    private boolean printOnly;

    public McpInstallCommand(ProcessRunner processes, StealthHome home) {
        this.processes = processes;
        this.home = home;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        String token =
                rotateToken ? McpToken.create(home.path()) : McpToken.loadOrCreate(home.path());
        String url = McpServer.url(port);
        List<String> add = claudeAddArguments(url, token);

        Optional<Path> claude = printOnly ? Optional.empty() : processes.find("claude");
        int exitCode = 0;
        if (claude.isPresent()) {
            // Replace an earlier registration (another port or token); fine if there's none
            processes.run(claude.get(), List.of("mcp", "remove", "--scope", "user", SERVER_NAME));
            ProcessRunner.Result result = processes.run(claude.get(), add);
            if (result.exitCode() == 0) {
                out.println("Registered with Claude Code for every repository (user scope).");
            } else {
                err.println("stealth mcp install: claude mcp add failed:");
                err.println(result.output().strip());
                exitCode = 1;
            }
        } else {
            out.println(
                    printOnly
                            ? "To register with Claude Code, run:"
                            : "Claude Code's claude command isn't on PATH. Once it is, run:");
            out.println("  claude " + String.join(" ", quoted(add)));
        }

        out.println();
        out.println("For Cursor, add this to ~/.cursor/mcp.json:");
        out.println(
                """
                {
                  "mcpServers": {
                    "stealth": {
                      "url": "%s",
                      "headers": { "Authorization": "Bearer %s" }
                    }
                  }
                }\
                """
                        .formatted(url, token));
        out.println();
        out.println(
                "Then start the server and keep it running: stealth mcp"
                        + (port == McpServer.DEFAULT_PORT ? "" : " --port " + port)
                        + (rotateToken ? " (restart it if it's running: the token changed)" : ""));
        out.println(
                "In Claude Code, ask e.g. \"how are this repo's vulnerabilities looking?\" and it"
                        + " will call stealth.");
        out.flush();
        err.flush();
        return exitCode;
    }

    static List<String> claudeAddArguments(String url, String token) {
        return List.of(
                "mcp",
                "add",
                "--transport",
                "http",
                "--scope",
                "user",
                SERVER_NAME,
                url,
                "--header",
                "Authorization: Bearer " + token);
    }

    private static List<String> quoted(List<String> arguments) {
        return arguments.stream().map(a -> a.contains(" ") ? "\"" + a + "\"" : a).toList();
    }
}
