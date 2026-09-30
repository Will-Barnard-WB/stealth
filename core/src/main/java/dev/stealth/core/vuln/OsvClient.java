package dev.stealth.core.vuln;

import dev.stealth.core.http.CachedHttpClient;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OSV.dev's API: which versions have advisories ({@code querybatch}), and the advisories. */
public class OsvClient {

    public static final URI OSV = URI.create("https://api.osv.dev/");

    /** OSV's limit on queries per batch. */
    static final int MAX_BATCH = 1000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CachedHttpClient http;
    private final URI api;
    private final int batchSize;

    public OsvClient(CachedHttpClient http, URI api) {
        this(http, api, MAX_BATCH);
    }

    OsvClient(CachedHttpClient http, URI api, int batchSize) {
        this.http = Objects.requireNonNull(http, "http");
        this.api = Objects.requireNonNull(api, "api");
        this.batchSize = batchSize;
    }

    /** A Maven artifact version, {@code name} being {@code groupId:artifactId}. */
    public record Package(String name, String version) {}

    /**
     * The advisory ids for each package, in a stable order (sorted by name, then version), with an
     * empty list for packages that have none.
     *
     * @throws IOException if OSV can't be reached and nothing is cached
     */
    public Map<Package, List<String>> query(Collection<Package> packages)
            throws IOException, InterruptedException {
        List<Package> sorted =
                packages.stream()
                        .distinct()
                        .sorted(Comparator.comparing(Package::name).thenComparing(Package::version))
                        .toList();
        Map<Package, List<String>> ids = new LinkedHashMap<>();
        for (int start = 0; start < sorted.size(); start += batchSize) {
            List<Package> batch = sorted.subList(start, Math.min(start + batchSize, sorted.size()));
            JsonNode results = post("v1/querybatch", batchRequest(batch)).path("results");
            for (int i = 0; i < batch.size(); i++) {
                JsonNode result = results.path(i);
                List<String> packageIds = new ArrayList<>();
                result.path("vulns").forEach(v -> packageIds.add(v.path("id").asString()));
                // A package with very many advisories is paged; fetch the rest one query at a time
                String pageToken = result.path("next_page_token").asString("");
                while (!pageToken.isEmpty()) {
                    ObjectNode query = query(batch.get(i));
                    query.put("page_token", pageToken);
                    JsonNode page = post("v1/query", JSON.writeValueAsString(query));
                    page.path("vulns").forEach(v -> packageIds.add(v.path("id").asString()));
                    pageToken = page.path("next_page_token").asString("");
                }
                ids.put(batch.get(i), List.copyOf(packageIds));
            }
        }
        return ids;
    }

    /** The advisory {@code id}. */
    public OsvVulnerability vulnerability(String id) throws IOException, InterruptedException {
        URI uri = api.resolve("v1/vulns/" + id);
        String body = http.get(uri).orElseThrow(() -> new IOException("OSV has no advisory " + id));
        try {
            return OsvVulnerability.parse(JSON.readTree(body));
        } catch (JacksonException e) {
            throw new IOException("Malformed OSV advisory " + id, e);
        }
    }

    /** The {@code querybatch} request body, exactly as sent. */
    static String batchRequest(List<Package> packages) {
        ObjectNode request = JSON.createObjectNode();
        ArrayNode queries = request.putArray("queries");
        packages.forEach(p -> queries.add(query(p)));
        return JSON.writeValueAsString(request);
    }

    private static ObjectNode query(Package p) {
        ObjectNode query = JSON.createObjectNode();
        query.putObject("package").put("ecosystem", "Maven").put("name", p.name());
        query.put("version", p.version());
        return query;
    }

    private JsonNode post(String path, String body) throws IOException, InterruptedException {
        URI uri = api.resolve(path);
        String response =
                http.postJson(uri, body)
                        .orElseThrow(() -> new IOException("OSV returned 404 for " + uri));
        try {
            return JSON.readTree(response);
        } catch (JacksonException e) {
            throw new IOException("Malformed OSV response from " + uri, e);
        }
    }
}
