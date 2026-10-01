package dev.stealth.cli;

import dev.stealth.mcp.McpServer;
import dev.stealth.mcp.McpToken;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code stealth mcp}: runs the MCP server that AI agents call, on 127.0.0.1 until stopped. It runs
 * as a child of this command's Spring context, so it shares the analyzers and HTTP cache.
 */
@Component
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        subcommands = {McpInstallCommand.class, McpStatusCommand.class},
        description = {
            "Run the MCP server that AI agents such as Claude Code call to check repositories"
                    + " and dependencies.",
            "",
            "Set up once with stealth mcp install, then keep stealth mcp running."
        })
public class McpCommand implements Callable<Integer> {

    private final ApplicationContext context;
    private final StealthHome home;

    @Spec private CommandSpec spec;

    @Option(
            names = "--port",
            paramLabel = "PORT",
            description = "Port on 127.0.0.1 to listen on. Default: ${DEFAULT-VALUE}.")
    private int port = McpServer.DEFAULT_PORT;

    public McpCommand(ApplicationContext context, StealthHome home) {
        this.context = context;
        this.home = home;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!McpServer.portFree(port)) {
            Optional<String> running = McpServer.runningVersion(port);
            err.println(
                    running.isPresent()
                            ? "stealth mcp: a stealth MCP server ("
                                    + running.get()
                                    + ") is already running on port "
                                    + port
                            : "stealth mcp: port "
                                    + port
                                    + " is in use by another program; pick another with --port"
                                    + " (and register it with stealth mcp install --port)");
            return 1;
        }
        String token = McpToken.loadOrCreate(home.path());

        ConfigurableApplicationContext server =
                McpServer.start(
                        (ConfigurableApplicationContext) context,
                        new McpServer.Settings(port, token, ManifestVersionProvider.version()));
        CountDownLatch closed = new CountDownLatch(1);
        server.addApplicationListener((ContextClosedEvent event) -> closed.countDown());

        out.println("stealth MCP server listening on " + McpServer.url(port));
        out.println(
                "Register it with Claude Code once: stealth mcp install"
                        + (port == McpServer.DEFAULT_PORT ? "" : " --port " + port));
        out.println("Tool calls are logged here.");
        out.println("Press Ctrl+C to stop.");
        out.flush();
        closed.await();
        return 0;
    }
}
