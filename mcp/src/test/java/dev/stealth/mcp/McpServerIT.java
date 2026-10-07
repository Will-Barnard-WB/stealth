package dev.stealth.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.Fixture;
import dev.stealth.core.clean.Cleaner;
import dev.stealth.core.clean.CleanupVerifier;
import dev.stealth.core.clean.PatchPlanner;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.SeededMavenRepository;
import dev.stealth.core.vuln.RecordedOsv;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server as an agent sees it: started on 127.0.0.1, reached by an MCP client over Streamable
 * HTTP, with every analyzer running against recorded remote data.
 */
@WireMockTest
class McpServerIT {

    private static final String TOKEN = "test-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir static Path seededRepository;
    @TempDir static Path cacheDirectory;

    private static GenericApplicationContext parent;
    private static ConfigurableApplicationContext server;
    private static int port;

    @BeforeAll
    static void startServer(WireMockRuntimeInfo wireMock) throws IOException {
        MavenModelLoader loader =
                new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
        AllAnalyzers.Components components =
                AllAnalyzers.components(wireMock.getHttpBaseUrl(), cacheDirectory, loader);
        // What the CLI's context provides to `stealth mcp`
        parent = new GenericApplicationContext();
        parent.registerBean(AnalyzerRunner.class, components::runner);
        parent.registerBean(MavenCentralClient.class, components::central);
        parent.registerBean(MavenCentralSearch.class, components::search);
        parent.registerBean(VulnerabilityAnalyzer.class, components::vulnerabilities);
        parent.registerBean(Clock.class, () -> AllAnalyzers.REFERENCE_DATE);
        PatchPlanner planner = new PatchPlanner(loader, components.vulnerabilities());
        parent.registerBean(PatchPlanner.class, () -> planner);
        parent.registerBean(Cleaner.class, () -> new Cleaner(planner, AllAnalyzers.REFERENCE_DATE));
        parent.registerBean(CleanupVerifier.class, () -> new CleanupVerifier(components.runner()));
        parent.registerBean(
                dev.stealth.core.impact.UpgradeImpact.class,
                () -> new dev.stealth.core.impact.UpgradeImpact(loader));
        parent.registerBean(
                dev.stealth.core.impact.TestGaps.class,
                () ->
                        new dev.stealth.core.impact.TestGaps(
                                loader, new dev.stealth.core.impact.UpgradeImpact(loader)));
        parent.refresh();

        port = freePort();
        server = McpServer.start(parent, new McpServer.Settings(port, TOKEN, "test"));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
        if (parent != null) {
            parent.close();
        }
    }

    @BeforeEach
    void stub() {
        // WireMock clears stubs before each test
        AllAnalyzers.stubAll();
        stubOsv(
                "org.apache.commons:commons-text",
                "1.9",
                "{\"vulns\":[{\"id\":\"GHSA-599f-7c49-w659\"}]}");
        stubOsv("org.apache.commons:commons-text", "1.15.0", "{}");
    }

    @Test
    void listTools_listsTheThreeReadOnlyTools() {
        try (McpSyncClient client = client(TOKEN)) {
            McpSchema.ListToolsResult tools = client.listTools();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "repo_health",
                            "list_findings",
                            "check_dependency",
                            "plan_cleanup",
                            "apply_cleanup",
                            "verify_cleanup",
                            "upgrade_impact",
                            "test_gaps");
            assertThat(tools.tools())
                    .allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
            // Only apply_cleanup writes anything (a new branch), and it's not destructive
            assertThat(tools.tools())
                    .filteredOn(tool -> !tool.name().equals("apply_cleanup"))
                    .allSatisfy(tool -> assertThat(tool.annotations().readOnlyHint()).isTrue());
            assertThat(tools.tools())
                    .filteredOn(tool -> tool.name().equals("apply_cleanup"))
                    .singleElement()
                    .satisfies(
                            tool -> {
                                assertThat(tool.annotations().readOnlyHint()).isFalse();
                                assertThat(tool.annotations().destructiveHint()).isFalse();
                            });
            McpSchema.Tool listFindings =
                    tools.tools().stream()
                            .filter(t -> t.name().equals("list_findings"))
                            .findFirst()
                            .orElseThrow();
            assertThat(listFindings.inputSchema().get("required"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly("path");
        }
    }

    @Test
    void repoHealth_boot2Legacy_returnsScoresAndTopFixesThenReusesTheScan() {
        try (McpSyncClient client = client(TOKEN)) {
            String path = Fixture.BOOT2_LEGACY.path().toString();

            JsonNode first = call(client, "repo_health", Map.of("path", path, "refresh", true));
            JsonNode second = call(client, "repo_health", Map.of("path", path));

            assertThat(first.at("/score/overall").asInt()).isEqualTo(50);
            assertThat(first.at("/score/security/status").asString()).isEqualTo("complete");
            assertThat(first.at("/findings/security/critical").asInt()).isPositive();
            assertThat(first.get("topFixes")).hasSize(StealthTools.TOP_FIXES);
            assertThat(first.at("/topFixes/0/what").asString()).startsWith("Upgrade ");
            assertThat(first.at("/analyzers/vuln").asString()).isEqualTo("ok");
            assertThat(first.get("fromCache").asBoolean()).isFalse();
            assertThat(second.get("fromCache").asBoolean()).isTrue();
            assertThat(second.get("score")).isEqualTo(first.get("score"));
        }
    }

    @Test
    void listFindings_criticalSecurity_returnsOnlyThoseWithTheirFixes() {
        try (McpSyncClient client = client(TOKEN)) {
            JsonNode result =
                    call(
                            client,
                            "list_findings",
                            Map.of(
                                    "path",
                                    Fixture.BOOT2_LEGACY.path().toString(),
                                    "category",
                                    "security",
                                    "severity",
                                    "critical",
                                    "limit",
                                    3));

            assertThat(result.get("total").asInt()).isGreaterThan(3);
            assertThat(result.get("findings")).hasSize(3);
            assertThat(result.get("findings").valueStream())
                    .allSatisfy(
                            f -> {
                                assertThat(f.get("severity").asString()).isEqualTo("critical");
                                assertThat(f.get("where").asString()).startsWith("pom.xml");
                            });
            assertThat(result.get("next").asString()).contains("Showing 3 of");
        }
    }

    @Test
    void checkDependency_vulnerableVersion_saysToAvoidItAndWhatFixesIt() {
        try (McpSyncClient client = client(TOKEN)) {
            JsonNode result =
                    call(
                            client,
                            "check_dependency",
                            Map.of(
                                    "groupId", "org.apache.commons",
                                    "artifactId", "commons-text",
                                    "version", "1.9"));

            assertThat(result.at("/vulnerabilities/0/id").asString())
                    .isEqualTo("GHSA-599f-7c49-w659");
            assertThat(result.at("/vulnerabilities/0/fixedVersion").asString()).isEqualTo("1.10.0");
            assertThat(result.get("latestVersion").asString()).isEqualTo("1.15.0");
            assertThat(result.get("verdict").asString())
                    .startsWith(
                            "Avoid 1.9: 1 known vulnerability, worst critical, all fixed by"
                                    + " 1.10.0.")
                    .contains(
                            "The latest stable version is 1.15.0, with no known vulnerabilities.");
        }
    }

    @Test
    void planCleanup_boot2Legacy_reportsTheVulnerabilitiesAndWhatCanBeFixed() {
        try (McpSyncClient client = client(TOKEN)) {
            JsonNode plan =
                    call(
                            client,
                            "plan_cleanup",
                            Map.of("path", Fixture.BOOT2_LEGACY.path().toString()));

            assertThat(plan.get("vulnerabilities").asInt()).isPositive();
            assertThat(plan.get("patches").isArray()).isTrue();
            assertThat(plan.get("needsMigration").isArray()).isTrue();
            assertThat(plan.get("next").asString()).isNotBlank();
        }
    }

    @Test
    void applyAndVerifyCleanup_outsideGit_areToolErrorsSayingWhy(@TempDir Path notGit)
            throws IOException {
        Path copy = Fixture.BOOT2_LEGACY.copyTo(notGit);
        try (McpSyncClient client = client(TOKEN)) {
            for (String tool : List.of("apply_cleanup", "verify_cleanup")) {
                McpSchema.CallToolResult result =
                        client.callTool(
                                new McpSchema.CallToolRequest(
                                        tool, Map.of("path", copy.toString(), "skipTests", true)));

                assertThat(result.isError()).as(tool).isTrue();
                assertThat(text(result)).as(tool).contains("isn't in a git repository");
            }
        }
    }

    @Test
    void repoHealth_relativePath_isAToolErrorTheAgentCanRead() {
        try (McpSyncClient client = client(TOKEN)) {
            McpSchema.CallToolResult result =
                    client.callTool(
                            new McpSchema.CallToolRequest(
                                    "repo_health", Map.of("path", "fixtures")));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("path must be absolute");
        }
    }

    @Test
    void request_withoutOrWithAWrongToken_is401() throws Exception {
        assertThat(ping(Map.of()).statusCode()).isEqualTo(401);
        assertThat(ping(Map.of("Authorization", "Bearer wrong")).statusCode()).isEqualTo(401);
        assertThat(ping(Map.of("Authorization", "Bearer " + TOKEN)).statusCode()).isNotIn(401, 403);
    }

    @Test
    void request_fromABrowserOrigin_is403EvenWithTheToken() throws Exception {
        HttpResponse<String> response =
                ping(Map.of("Authorization", "Bearer " + TOKEN, "Origin", "https://evil.example"));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void request_withAForeignHost_is403SoDnsRebindingFails() throws Exception {
        // java.net.http won't send a custom Host header, so speak HTTP directly
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream()
                    .write(
                            ("GET /status HTTP/1.1\r\nHost: evil.example:"
                                            + port
                                            + "\r\nConnection: close\r\n\r\n")
                                    .getBytes());
            String response = new String(socket.getInputStream().readAllBytes());

            assertThat(response).startsWith("HTTP/1.1 403");
        }
    }

    @Test
    void status_needsNoTokenAndIdentifiesStealth() {
        assertThat(McpServer.runningVersion(port)).contains("test");
        assertThat(McpServer.portFree(port)).isFalse();
    }

    @Test
    void server_listensOnLoopbackOnly() throws Exception {
        Optional<InetAddress> external =
                NetworkInterface.networkInterfaces()
                        .filter(
                                i -> {
                                    try {
                                        return i.isUp() && !i.isLoopback();
                                    } catch (IOException e) {
                                        return false;
                                    }
                                })
                        .flatMap(NetworkInterface::inetAddresses)
                        .filter(a -> a instanceof Inet4Address)
                        .findFirst();
        assumeThat(external).as("a non-loopback IPv4 address").isPresent();

        try (Socket socket = new Socket()) {
            assertThat(tryConnect(socket, new InetSocketAddress(external.get(), port)))
                    .as("connect to %s:%d", external.get(), port)
                    .isFalse();
        }
    }

    private static boolean tryConnect(Socket socket, InetSocketAddress address) {
        try {
            socket.connect(address, 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .requestBuilder(
                                HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                        .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(120)).build();
        client.initialize();
        return client;
    }

    private static JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as(() -> text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static HttpResponse<String> ping(Map<String, String> headers) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        headers.forEach(request::header);
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void stubOsv(String name, String version, String result) {
        stubFor(
                post(urlEqualTo(RecordedOsv.BASE_PATH + "v1/querybatch"))
                        .atPriority(1)
                        .withRequestBody(
                                equalToJson(
                                        "{\"queries\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\""
                                                + name
                                                + "\"},\"version\":\""
                                                + version
                                                + "\"}]}"))
                        .willReturn(aResponse().withBody("{\"results\":[" + result + "]}")));
    }
}
