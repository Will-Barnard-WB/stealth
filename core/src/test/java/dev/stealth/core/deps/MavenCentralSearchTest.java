package dev.stealth.core.deps;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class MavenCentralSearchTest {

    @TempDir private Path cacheDirectory;

    private MavenCentralSearch search;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        search =
                new MavenCentralSearch(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedCentral.SEARCH_PATH));
    }

    @Test
    void published_recordedRelease_returnsItsTimestamp() throws Exception {
        RecordedCentral.stubAll();

        assertThat(search.published("commons-collections", "commons-collections", "3.2.2"))
                .contains(Instant.parse("2015-11-12T23:11:26Z"));
    }

    @Test
    void published_releaseNotIndexedYet_returnsEmpty() throws Exception {
        RecordedCentral.stubAll();

        // guava 33.7.2-jre came out on the reference date and wasn't in the index yet
        assertThat(search.published("com.google.guava", "guava", "33.7.2-jre")).isEmpty();
    }

    @Test
    void published_sameVersionTwice_isServedFromTheCache() throws Exception {
        stubFor(
                get(urlPathEqualTo(RecordedCentral.SEARCH_PATH))
                        .withQueryParam("core", equalTo("gav"))
                        .willReturn(okJson("{\"response\":{\"docs\":[{\"timestamp\":0}]}}")));

        search.published("org.example", "lib", "1.0");
        search.published("org.example", "lib", "1.0");

        verify(exactly(1), getRequestedFor(urlPathEqualTo(RecordedCentral.SEARCH_PATH)));
    }

    @Test
    void published_malformedResponse_throws() {
        stubFor(get(urlPathEqualTo(RecordedCentral.SEARCH_PATH)).willReturn(ok("<html>")));

        assertThatThrownBy(() -> search.published("org.example", "lib", "1.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed Maven Central search response");
    }
}
