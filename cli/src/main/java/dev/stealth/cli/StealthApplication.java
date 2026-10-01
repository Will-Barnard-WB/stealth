package dev.stealth.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * The CLI's context. It never serves MCP itself: {@code stealth mcp} starts a child web context for
 * that ({@code McpServer}), so Spring AI's MCP auto-configuration is excluded here, keeping other
 * commands' startup and stdout clean. {@code StealthApplicationTest} fails if an upgrade adds one
 * this list misses.
 */
@SpringBootApplication(
        excludeName = {
            "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration",
            "org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration",
            "org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerSseWebMvcAutoConfiguration",
            "org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration",
            "org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration"
        })
public class StealthApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory factory;
    private final StealthCommand command;
    private int exitCode;

    public StealthApplication(IFactory factory, StealthCommand command) {
        this.factory = factory;
        this.command = command;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(StealthApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        exitCode = StealthCli.configure(new CommandLine(command, factory)).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
