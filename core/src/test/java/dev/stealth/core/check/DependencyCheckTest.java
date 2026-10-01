package dev.stealth.core.check;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.Severity;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.deps.RecordedCentral;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import dev.stealth.core.vuln.OsvClient;
import dev.stealth.core.vuln.RecordedOsv;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class DependencyCheckTest {

    @TempDir private Path cacheDirectory;

    private DependencyCheck check;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        RecordedCentral.stubAll();
        RecordedOsv.stubAll();
        stubOsv(
                "org.apache.commons:commons-text",
                "1.9",
                "{\"vulns\":[{\"id\":\"GHSA-599f-7c49-w659\"}]}");
        stubOsv("org.apache.commons:commons-text", "1.15.0", "{}");
        String base = wireMock.getHttpBaseUrl();
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        VulnerabilityAnalyzer vulnerabilities =
                new VulnerabilityAnalyzer(
                        new MavenModelLoader(MavenResolverSettings.defaults()),
                        new OsvClient(http, URI.create(base + RecordedOsv.BASE_PATH)));
        check =
                new DependencyCheck(
                        new MavenCentralClient(http, URI.create(base + RecordedCentral.BASE_PATH)),
                        new MavenCentralSearch(
                                http, URI.create(base + RecordedCentral.SEARCH_PATH)),
                        vulnerabilities,
                        AllAnalyzers.REFERENCE_DATE);
    }

    @Test
    void check_vulnerableVersion_reportsTheAdvisoryItsFixAndTheLatestRelease() throws Exception {
        DependencyCheck.Result result =
                check.check("org.apache.commons", "commons-text", Optional.of("1.9"));

        assertThat(result.found()).isTrue();
        assertThat(result.version()).contains("1.9");
        assertThat(result.latestVersion()).contains("1.15.0");
        assertThat(result.latestReleased()).isPresent();
        assertThat(result.maintained()).contains(true);
        assertThat(result.vulnerabilities())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                            assertThat(f.advisory().orElseThrow().id())
                                    .isEqualTo("GHSA-599f-7c49-w659");
                            assertThat(f.advisory().orElseThrow().aliases())
                                    .contains("CVE-2022-42889");
                            assertThat(f.remediation().flatMap(r -> r.fixedVersion()))
                                    .contains("1.10.0");
                            assertThat(f.location().path()).isEmpty();
                        });
        assertThat(result.latestVulnerabilities()).contains(0);
    }

    @Test
    void check_noVersion_checksTheLatest() throws Exception {
        DependencyCheck.Result result =
                check.check("org.apache.commons", "commons-text", Optional.empty());

        assertThat(result.version()).contains("1.15.0");
        assertThat(result.vulnerabilities()).isEmpty();
        assertThat(result.latestVulnerabilities()).isEmpty();
    }

    @Test
    void check_unmaintainedArtifact_isNotMaintained() throws Exception {
        stubOsv("commons-collections:commons-collections", "3.2.2", "{}");

        DependencyCheck.Result result =
                check.check("commons-collections", "commons-collections", Optional.empty());

        assertThat(result.maintained()).contains(false);
        assertThat(result.latestVersion()).contains("3.2.2");
    }

    @Test
    void check_artifactNotOnCentral_isNotFound() throws Exception {
        stubFor(
                get(urlEqualTo(RecordedCentral.BASE_PATH + "com/example/nope/maven-metadata.xml"))
                        .atPriority(1)
                        .willReturn(aResponse().withStatus(404)));

        DependencyCheck.Result result = check.check("com.example", "nope", Optional.empty());

        assertThat(result.found()).isFalse();
        assertThat(result.latestVersion()).isEmpty();
        assertThat(result.vulnerabilities()).isEmpty();
    }

    @Test
    void check_invalidCoordinate_isRejectedBeforeAnyLookup() {
        assertThatThrownBy(
                        () ->
                                check.check(
                                        "org.apache.commons",
                                        "commons-text/../x",
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactId must be a Maven coordinate");
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
