package dev.stealth.core.deps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class MavenCentralClientTest {

    @TempDir private Path cacheDirectory;

    private MavenCentralClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        client =
                new MavenCentralClient(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedCentral.BASE_PATH));
    }

    @Test
    void versions_recordedMetadata_listsEveryVersionInOrder() throws Exception {
        RecordedCentral.stubAll();

        List<String> versions =
                client.versions("commons-collections", "commons-collections").orElseThrow();

        assertThat(versions)
                .startsWith("1.0")
                .endsWith(
                        "3.2.2",
                        "20030418.083655",
                        "20031027.000000",
                        "20040102.233541",
                        "20040616")
                .hasSize(17);
    }

    @Test
    void versions_artifactNotInRepository_returnsEmpty() throws Exception {
        stubFor(
                get("/maven2/com/example/internal/maven-metadata.xml")
                        .willReturn(aResponse().withStatus(404)));

        assertThat(client.versions("com.example", "internal")).isEmpty();
    }

    @Test
    void versions_malformedMetadata_throws() {
        stubFor(get("/maven2/com/example/broken/maven-metadata.xml").willReturn(ok("<metadata>")));

        assertThatThrownBy(() -> client.versions("com.example", "broken"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed maven-metadata.xml");
    }

    @Test
    void parseVersions_doctypeDeclaration_isRejected() {
        String xxe =
                """
                <?xml version="1.0"?>
                <!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>
                """;

        assertThatThrownBy(() -> MavenCentralClient.parseVersions(xxe, URI.create("http://x")))
                .isInstanceOf(IOException.class);
    }
}
