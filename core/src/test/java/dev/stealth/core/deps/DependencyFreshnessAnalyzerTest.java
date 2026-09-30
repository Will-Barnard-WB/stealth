package dev.stealth.core.deps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.Remediation;
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
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Precision tests against the fixtures, with Maven Central pinned to the reference date. */
@WireMockTest
class DependencyFreshnessAnalyzerTest {

    @TempDir static Path seededRepository;

    private static MavenModelLoader loader;

    @TempDir private Path cacheDirectory;

    private DependencyFreshnessAnalyzer analyzer;

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
        MavenCentralClient central =
                new MavenCentralClient(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedCentral.BASE_PATH));
        analyzer = new DependencyFreshnessAnalyzer(loader, central);
    }

    @Test
    void analyze_boot2Legacy_flagsThePinnedOutdatedDependencies() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.BOOT2_LEGACY.context());

        assertThat(findings)
                .extracting(DependencyFreshnessAnalyzerTest::line, Finding::ruleId, f -> latest(f))
                .containsExactlyInAnyOrder(
                        Tuple.tuple(10, "deps/outdated-major", "4.1.1"),
                        Tuple.tuple(30, "deps/outdated-minor", "3.21.0"),
                        Tuple.tuple(35, "deps/outdated-major", "33.7.2-jre"),
                        Tuple.tuple(40, "deps/outdated-minor", "1.15.0"));
    }

    @Test
    void analyze_boot2Legacy_collapsesBootManagedStartersIntoTheParent() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.BOOT2_LEGACY.context());

        Finding parent = findings.stream().filter(f -> line(f) == 10).findFirst().orElseThrow();
        assertThat(parent.message())
                .startsWith(
                        "org.springframework.boot:spring-boot-starter-parent 2.7.18 is outdated")
                .contains("manages 2 of the module's dependencies");
        assertThat(parent.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(parent.component())
                .contains("pkg:maven/org.springframework.boot/spring-boot-starter-parent@2.7.18");
        assertThat(findings).noneMatch(f -> f.message().contains("spring-boot-starter-web"));
    }

    @Test
    void analyze_boot2Legacy_doesNotFlagCommonsCollectionsDateStampedRelease() throws Exception {
        assertThat(analyzer.analyze(Fixture.BOOT2_LEGACY.context()))
                .noneMatch(f -> f.message().contains("commons-collections"));
    }

    @Test
    void analyze_boot4Clean_reportsNothing() throws Exception {
        assertThat(analyzer.analyze(Fixture.BOOT4_CLEAN.context())).isEmpty();
    }

    @Test
    void analyze_multiModule_reportsEachVersionOnceWhereItIsSet() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.MULTI_MODULE.context());

        assertThat(findings)
                .extracting(
                        f -> f.location().path().orElseThrow(),
                        DependencyFreshnessAnalyzerTest::line,
                        f -> latest(f))
                .containsExactlyInAnyOrder(
                        Tuple.tuple("service/pom.xml", 17, "33.7.2-jre"),
                        Tuple.tuple("pom.xml", 48, "3.21.0"),
                        Tuple.tuple("app/pom.xml", 27, "3.21.0"));
    }

    @Test
    void analyze_multiModule_fingerprintsDifferByModule() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.MULTI_MODULE.context());

        assertThat(findings)
                .filteredOn(f -> f.message().startsWith("org.apache.commons:commons-lang3"))
                .extracting(Finding::fingerprint)
                .doesNotHaveDuplicates()
                .hasSize(2);
    }

    @Test
    void analyze_artifactNotOnCentral_skipsIt() throws Exception {
        stubFor(
                get(urlEqualTo(
                                RecordedCentral.BASE_PATH
                                        + "org/apache/commons/commons-text/maven-metadata.xml"))
                        .atPriority(0)
                        .willReturn(aResponse().withStatus(404)));

        assertThat(analyzer.analyze(Fixture.BOOT2_LEGACY.context()))
                .noneMatch(f -> f.message().contains("commons-text"))
                .hasSize(3);
    }

    @Test
    void analyze_centralFailing_failsInsteadOfReportingUpToDate() {
        stubFor(
                get(urlEqualTo(
                                RecordedCentral.BASE_PATH
                                        + "com/google/guava/guava/maven-metadata.xml"))
                        .atPriority(0)
                        .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> analyzer.analyze(Fixture.BOOT2_LEGACY.context()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("com.google.guava:guava");
    }

    private static int line(Finding finding) {
        return finding.location().line().orElseThrow();
    }

    private static String latest(Finding finding) {
        return finding.remediation().flatMap(Remediation::fixedVersion).orElseThrow();
    }
}
