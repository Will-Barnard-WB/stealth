package dev.stealth.core.deps;

import dev.stealth.core.http.CachedHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * When a version was published, from Maven Central's search API. The repository's own {@code
 * Last-Modified} dates aren't usable for this: files are re-touched by storage migrations, so
 * commons-collections 3.2.2 (released 2015) reports 2025.
 */
public class MavenCentralSearch {

    public static final URI SEARCH = URI.create("https://search.maven.org/solrsearch/select");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CachedHttpClient http;
    private final URI endpoint;

    public MavenCentralSearch(CachedHttpClient http, URI endpoint) {
        this.http = Objects.requireNonNull(http, "http");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /**
     * When {@code groupId:artifactId:version} was published, or empty if the search index doesn't
     * have it. The index lags Maven Central by days to weeks, so a missing recent release is
     * normal.
     *
     * @throws IOException if the search API can't be reached and nothing is cached
     */
    public Optional<Instant> published(String groupId, String artifactId, String version)
            throws IOException, InterruptedException {
        String query =
                "g:\"" + groupId + "\" AND a:\"" + artifactId + "\" AND v:\"" + version + "\"";
        URI uri =
                URI.create(
                        endpoint
                                + "?q="
                                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                                        .replace("+", "%20")
                                + "&core=gav&rows=1&wt=json");
        String body =
                http.get(uri)
                        .orElseThrow(() -> new IOException("Maven Central search returned 404"));
        try {
            JsonNode docs = JSON.readTree(body).path("response").path("docs");
            if (docs.isEmpty() || !docs.path(0).path("timestamp").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochMilli(docs.path(0).path("timestamp").asLong()));
        } catch (JacksonException e) {
            throw new IOException("Malformed Maven Central search response for " + query, e);
        }
    }
}
