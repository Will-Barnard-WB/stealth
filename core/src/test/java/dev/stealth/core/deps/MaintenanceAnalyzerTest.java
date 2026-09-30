package dev.stealth.core.deps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.Severity;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.SeededMavenRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Precision tests against the fixtures, with Maven Central pinned to the reference date. */
@WireMockTest
class MaintenanceAnalyzerTest {

    /** The fixtures' reference date. */
    private static final Clock REFERENCE_DATE =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);

    @TempDir static Path seededRepository;

    private static MavenModelLoader loader;

    @TempDir private Path cacheDirectory;

    private MavenCentralClient central;
    private MavenCentralSearch search;

    @BeforeAll
    static void createLoader() {
        loader = new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
    }

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        RecordedCentral.stubAll();
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        central =
                new MavenCentralClient(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedCentral.BASE_PATH));
        search =
                new MavenCentralSearch(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedCentral.SEARCH_PATH));
    }

    @Test
    void analyze_boot2Legacy_flagsCommonsCollectionsOnly() throws Exception {
        List<Finding> findings =
                analyzer(Period.ofYears(2)).analyze(Fixture.BOOT2_LEGACY.context());

        assertThat(findings)
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.ruleId()).isEqualTo("maintenance/no-recent-release");
                            assertThat(finding.severity()).isEqualTo(Severity.LOW);
                            assertThat(finding.location().line()).isEqualTo(OptionalInt.of(45));
                            assertThat(finding.message())
                                    .isEqualTo(
                                            "commons-collections:commons-collections: no release in"
                                                    + " 10 years (newest is 3.2.2, released"
                                                    + " 2015-11-12)");
                        });
    }

    @Test
    void analyze_boot4Clean_reportsNothing() throws Exception {
        assertThat(analyzer(Period.ofYears(2)).analyze(Fixture.BOOT4_CLEAN.context())).isEmpty();
    }

    @Test
    void analyze_multiModule_reportsNothing() throws Exception {
        assertThat(analyzer(Period.ofYears(2)).analyze(Fixture.MULTI_MODULE.context())).isEmpty();
    }

    @Test
    void analyze_longerThreshold_doesNotFlagADecadeOldRelease() throws Exception {
        assertThat(analyzer(Period.ofYears(15)).analyze(Fixture.BOOT2_LEGACY.context())).isEmpty();
    }

    @Test
    void analyze_shorterThreshold_alsoFlagsReleasesOlderThanIt() throws Exception {
        // commons-text 1.15.0 was released 2025-12-04, under a year before the reference date
        List<Finding> findings =
                analyzer(Period.ofMonths(6)).analyze(Fixture.BOOT2_LEGACY.context());

        assertThat(findings)
                .extracting(f -> f.location().line().getAsInt())
                .containsExactlyInAnyOrder(40, 45);
    }

    @Test
    void analyze_searchFailing_failsInsteadOfReportingEverythingMaintained() {
        stubFor(
                get(urlPathEqualTo(RecordedCentral.SEARCH_PATH))
                        .atPriority(0)
                        .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(
                        () -> analyzer(Period.ofYears(2)).analyze(Fixture.BOOT2_LEGACY.context()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("couldn't check");
    }

    private MaintenanceAnalyzer analyzer(Period staleAfter) {
        return new MaintenanceAnalyzer(loader, central, search, staleAfter, REFERENCE_DATE);
    }
}
