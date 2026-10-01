package dev.stealth.core.vuln;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import dev.stealth.core.Fixture;
import dev.stealth.core.TestResources;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenProjectModel;
import dev.stealth.core.maven.SeededMavenRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OSV.dev responses for the fixtures, recorded and pinned to the fixtures' reference date:
 * advisories published after 2026-09-29 are removed, and fields stealth doesn't read are trimmed.
 * Stored under {@code __files/osv/}.
 *
 * <p>To re-record after changing a fixture's dependencies, run {@link #main} from the repository
 * root. It needs {@code maven-repo.zip} to cover the fixtures (see {@link SeededMavenRepository}).
 */
public final class RecordedOsv {

    public static final String BASE_PATH = "/osv/";

    private static final Instant REFERENCE_DATE = Instant.parse("2026-09-30T00:00:00Z");
    private static final List<Fixture> FIXTURES =
            List.of(Fixture.BOOT2_LEGACY, Fixture.BOOT4_CLEAN, Fixture.MULTI_MODULE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RecordedOsv() {}

    /**
     * Serves the recorded batch queries (matched on the exact request body) and advisories, and
     * answers anything else with a 500 so an unrecorded query fails the test.
     */
    public static void stubAll() {
        stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(500)));
        Path root = root();
        try (Stream<Path> files = Files.walk(root.resolve("querybatch"))) {
            for (Path request :
                    files.filter(f -> f.toString().endsWith(".request.json")).toList()) {
                Path response =
                        request.resolveSibling(
                                request.getFileName()
                                        .toString()
                                        .replace(".request.", ".response."));
                stubFor(
                        post(urlEqualTo(BASE_PATH + "v1/querybatch"))
                                .atPriority(1)
                                .withRequestBody(equalToJson(Files.readString(request)))
                                .willReturn(aResponse().withBody(Files.readString(response))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Stream<Path> files = Files.walk(root.resolve("vulns"))) {
            for (Path advisory : files.filter(Files::isRegularFile).toList()) {
                String id = advisory.getFileName().toString().replace(".json", "");
                stubFor(
                        get(urlEqualTo(BASE_PATH + "v1/vulns/" + id))
                                .atPriority(1)
                                .willReturn(aResponse().withBody(Files.readString(advisory))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("core/src/test/resources/__files/osv");
        Path repository = Files.createTempDirectory("stealth-seed");
        MavenModelLoader loader = new MavenModelLoader(SeededMavenRepository.extractTo(repository));
        HttpClient http = HttpClient.newHttpClient();
        Files.createDirectories(out.resolve("querybatch"));
        Files.createDirectories(out.resolve("vulns"));

        for (Fixture fixture : FIXTURES) {
            MavenProjectModel model = loader.load(fixture.path());
            List<OsvClient.Package> packages =
                    VulnerabilityAnalyzer.occurrences(model).stream()
                            .map(VulnerabilityAnalyzer.Occurrence::pkg)
                            .distinct()
                            .sorted(
                                    Comparator.comparing(OsvClient.Package::name)
                                            .thenComparing(OsvClient.Package::version))
                            .toList();
            String request = OsvClient.batchRequest(packages);
            JsonNode response =
                    JSON.readTree(send(http, "https://api.osv.dev/v1/querybatch", request));

            ObjectNode pinned = JSON.createObjectNode();
            ArrayNode results = pinned.putArray("results");
            Set<String> kept = new LinkedHashSet<>();
            for (JsonNode result : response.path("results")) {
                if (result.has("next_page_token")) {
                    throw new IllegalStateException("paged result; record by hand");
                }
                ObjectNode pinnedResult = results.addObject();
                List<JsonNode> vulns = new ArrayList<>();
                for (JsonNode vuln : result.path("vulns")) {
                    String id = vuln.path("id").asString();
                    String advisory = send(http, "https://api.osv.dev/v1/vulns/" + id, null);
                    Instant published =
                            Instant.parse(JSON.readTree(advisory).path("published").asString());
                    if (published.isBefore(REFERENCE_DATE)) {
                        vulns.add(vuln);
                        kept.add(id);
                        Files.writeString(
                                out.resolve("vulns/" + id + ".json"), trimmed(advisory) + "\n");
                    }
                }
                if (!vulns.isEmpty()) {
                    pinnedResult.putArray("vulns").addAll(vulns);
                }
            }
            String name = fixture.directoryName().toLowerCase(Locale.ROOT);
            Files.writeString(out.resolve("querybatch/" + name + ".request.json"), request);
            Files.writeString(
                    out.resolve("querybatch/" + name + ".response.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(pinned));
            System.out.printf(
                    "%s: %d packages, %d advisories%n", fixture, packages.size(), kept.size());
        }
    }

    /**
     * The advisory without the fields stealth doesn't read (long write-ups, references, enumerated
     * version lists), which are most of its size.
     */
    private static String trimmed(String advisory) {
        ObjectNode json = (ObjectNode) JSON.readTree(advisory);
        json.remove(List.of("details", "references", "credits", "schema_version"));
        JsonNode severity = json.path("database_specific").path("severity");
        json.remove("database_specific");
        if (severity.isString()) {
            json.putObject("database_specific").put("severity", severity.asString());
        }
        for (JsonNode affected : json.path("affected")) {
            ((ObjectNode) affected)
                    .remove(List.of("versions", "database_specific", "ecosystem_specific"));
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json);
    }

    private static String send(HttpClient http, String url, String postBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (postBody != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(postBody));
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private static Path root() {
        return TestResources.path("/__files/osv");
    }
}
