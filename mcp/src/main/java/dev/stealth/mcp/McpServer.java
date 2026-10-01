package dev.stealth.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the MCP server on 127.0.0.1, and checks whether one is already running. */
public final class McpServer {

    public static final int DEFAULT_PORT = 7331;

    private static final Pattern STEALTH_STATUS =
            Pattern.compile(
                    "\"name\"\\s*:\\s*\"stealth\".*?\"version\"\\s*:\\s*\"([^\"]*)\"|"
                            + "\"version\"\\s*:\\s*\"([^\"]*)\".*?\"name\"\\s*:\\s*\"stealth\"");

    private McpServer() {}

    /** Logs go to the console of the process running the server. */
    public record Settings(int port, String token, String version) {}

    public static String url(int port) {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /**
     * Starts the server as a child of {@code parent}, which must provide the analyzers and remote
     * clients. Returns once it's listening.
     */
    public static ConfigurableApplicationContext start(
            ConfigurableApplicationContext parent, Settings settings) {
        // Command-line arguments outrank every config file, including the CLI's
        List<String> args = new ArrayList<>();
        args.add("--spring.config.name=stealth-mcp");
        args.add("--server.port=" + settings.port());
        args.add("--stealth.mcp.token=" + settings.token());
        args.add("--spring.ai.mcp.server.version=" + settings.version());
        return new SpringApplicationBuilder(McpServerApplication.class)
                .parent(parent)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));
    }

    /** Whether nothing is listening on {@code port} on the loopback interface. */
    public static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The version of the stealth server on {@code port}, if one is running there. */
    public static Optional<String> runningVersion(int port) {
        try (HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + LocalOnlyFilter.STATUS_PATH))
                                    .timeout(Duration.ofSeconds(2))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            Matcher matcher = STEALTH_STATUS.matcher(response.body());
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
