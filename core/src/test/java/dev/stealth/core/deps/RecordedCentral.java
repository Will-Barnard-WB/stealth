package dev.stealth.core.deps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Maven Central's {@code maven-metadata.xml} for the fixtures' dependencies, recorded and pinned to
 * the fixtures' reference date (2026-09-29): versions uploaded after it are removed. Stored under
 * {@code __files/central/}, in Central's own layout.
 */
final class RecordedCentral {

    static final String BASE_PATH = "/maven2/";

    /** Where the search API is served, mirroring {@code search.maven.org}. */
    static final String SEARCH_PATH = "/solrsearch/select";

    private RecordedCentral() {}

    /**
     * Serves every recorded file, and answers anything else with a 500 so a lookup that wasn't
     * recorded fails the test rather than silently counting as "not on Central".
     */
    static void stubAll() {
        stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(500)));
        Path root = root();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                stubFor(
                        get(urlEqualTo(BASE_PATH + path))
                                .atPriority(1)
                                .willReturn(aResponse().withBody(Files.readString(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        stubSearch();
    }

    /**
     * Search API responses recorded under {@code __files/central-search/<groupId>/<artifactId>/
     * <version>.json}, matched on the query the client sends.
     */
    private static void stubSearch() {
        Path searchRoot = root().resolveSibling("central-search");
        try (Stream<Path> files = Files.walk(searchRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = searchRoot.relativize(file);
                String groupId = relative.getName(0).toString();
                String artifactId = relative.getName(1).toString();
                String version = relative.getName(2).toString().replaceFirst("\\.json$", "");
                stubFor(
                        get(urlPathEqualTo(SEARCH_PATH))
                                .withQueryParam(
                                        "q",
                                        equalTo(
                                                "g:\""
                                                        + groupId
                                                        + "\" AND a:\""
                                                        + artifactId
                                                        + "\" AND v:\""
                                                        + version
                                                        + "\""))
                                .atPriority(1)
                                .willReturn(aResponse().withBody(Files.readString(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path root() {
        try {
            return Path.of(RecordedCentral.class.getResource("/__files/central").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
