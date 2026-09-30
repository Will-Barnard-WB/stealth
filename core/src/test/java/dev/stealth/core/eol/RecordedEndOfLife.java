package dev.stealth.core.eol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * endoflife.date's responses for the products the fixtures use, recorded and pinned to the
 * fixtures' reference date (2026-09-29). Stored under {@code __files/endoflife/}.
 */
final class RecordedEndOfLife {

    static final String BASE_PATH = "/api/";

    private RecordedEndOfLife() {}

    /**
     * Serves every recorded product, and answers anything else with a 500 so a product that wasn't
     * recorded fails the test rather than silently counting as "not tracked".
     */
    static void stubAll() {
        stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(500)));
        Path root = root();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                stubFor(
                        get(urlEqualTo(BASE_PATH + root.relativize(file).getFileName()))
                                .atPriority(1)
                                .willReturn(
                                        aResponse()
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(Files.readString(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String body(String product) {
        try {
            return Files.readString(root().resolve(product + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path root() {
        try {
            return Path.of(RecordedEndOfLife.class.getResource("/__files/endoflife").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
