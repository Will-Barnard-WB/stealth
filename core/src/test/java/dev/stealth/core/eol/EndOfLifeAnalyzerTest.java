package dev.stealth.core.eol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Precision tests against the fixtures, with endoflife.date pinned to the reference date. */
@WireMockTest
class EndOfLifeAnalyzerTest {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 9, 29);

    @TempDir static Path seededRepository;

    private static MavenModelLoader loader;

    @TempDir private Path cacheDirectory;

    private URI api;

    @BeforeAll
    static void createLoader() {
        loader = new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
    }

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        RecordedEndOfLife.stubAll();
        api = URI.create(wireMock.getHttpBaseUrl() + RecordedEndOfLife.BASE_PATH);
    }

    @Test
    void analyze_boot2Legacy_flagsSpringBoot2AtTheParentVersion() throws Exception {
        List<Finding> findings = analyzer(REFERENCE_DATE).analyze(Fixture.BOOT2_LEGACY.context());

        assertThat(findings).hasSize(1);
        Finding boot = findings.getFirst();
        assertThat(boot.ruleId()).isEqualTo("eol/past-end-of-life");
        assertThat(boot.severity()).isEqualTo(Severity.HIGH);
        assertThat(boot.location().path()).contains("pom.xml");
        assertThat(boot.location().line()).hasValue(10);
        assertThat(boot.message())
                .isEqualTo(
                        "uses Spring Boot 2.7, which reached end of life on 2023-06-30."
                                + " The oldest supported release is Spring Boot 4.1 (4.1.1)."
                                + " Commercial support runs to 2029-06-30.");
        assertThat(boot.component())
                .contains("pkg:maven/org.springframework.boot/spring-boot@2.7.18");
        assertThat(boot.remediation().orElseThrow().fixedVersion()).contains("4.1.1");
    }

    @Test
    void analyze_boot2LegacyOnTemurin_doesNotFlagJava11Yet() throws Exception {
        assertThat(analyzer(REFERENCE_DATE).analyze(Fixture.BOOT2_LEGACY.context()))
                .noneMatch(finding -> finding.message().contains("Java"));
    }

    @Test
    void analyze_boot2LegacyOnOracleJdk_flagsJava11() throws Exception {
        RepoContext context =
                new RepoContext(
                        Fixture.BOOT2_LEGACY.path(),
                        new StealthConfig(
                                Set.of(), StealthConfig.Eol.javaDistribution("oracle-jdk")));

        List<Finding> findings = analyzer(REFERENCE_DATE).analyze(context);

        assertThat(findings)
                .extracting(f -> f.location().line().orElseThrow(), Finding::ruleId)
                .contains(Tuple.tuple(19, "eol/past-end-of-life"));
        Finding java = java(findings);
        assertThat(java.message())
                .isEqualTo(
                        "targets Java 11 (oracle-jdk), which reached end of life on 2023-09-30."
                                + " The oldest supported release is Java 21 (21.0.12.1)."
                                + " Commercial support runs to 2032-01-31.");
        assertThat(java.component()).isEmpty();
    }

    @Test
    void analyze_boot4Clean_reportsNothing() throws Exception {
        assertThat(analyzer(REFERENCE_DATE).analyze(Fixture.BOOT4_CLEAN.context())).isEmpty();
    }

    @Test
    void analyze_boot4CleanInsideTheWarningWindow_warnsAboutSpringBoot() throws Exception {
        List<Finding> findings =
                analyzer(LocalDate.of(2027, 1, 31)).analyze(Fixture.BOOT4_CLEAN.context());

        assertThat(findings)
                .extracting(Finding::ruleId, Finding::severity, Finding::message)
                .containsExactly(
                        Tuple.tuple(
                                "eol/approaching-end-of-life",
                                Severity.LOW,
                                "uses Spring Boot 4.1, which reaches end of life on 2027-07-31."
                                        + " Commercial support runs to 2028-07-31."));
    }

    @ParameterizedTest
    @CsvSource({
        "2027-04-29, ",
        "2027-04-30, eol/approaching-end-of-life",
        "2027-10-31, eol/approaching-end-of-life",
        "2027-11-01, eol/past-end-of-life",
    })
    void analyze_temurin11_reportsOnlyInsideTheSixMonthWindowAndAfter(
            LocalDate today, String expected) throws Exception {
        List<Finding> findings =
                analyzer(today).analyze(Fixture.BOOT2_LEGACY.context()).stream()
                        .filter(finding -> finding.message().contains("Java"))
                        .toList();

        assertThat(findings)
                .extracting(Finding::ruleId)
                .containsExactlyElementsOf(expected == null ? List.of() : List.of(expected));
    }

    @Test
    void analyze_configuredRuntimeJavaVersion_reportsItInsteadOfTheCompileTarget()
            throws Exception {
        RepoContext context =
                new RepoContext(
                        Fixture.BOOT4_CLEAN.path(),
                        new StealthConfig(
                                Set.of(), new StealthConfig.Eol("oracle-jdk", Optional.of("17"))));

        Finding java = java(analyzer(REFERENCE_DATE).analyze(context));

        assertThat(java.message())
                .startsWith(
                        "runs on Java 17 (oracle-jdk), which reaches end of life on 2026-09-30.")
                .contains("The oldest supported release is Java 21 (21.0.12.1).");
        assertThat(java.location().path()).isEmpty();
    }

    @Test
    void analyze_multiModule_reportsTheParentJavaVersionOnce() throws Exception {
        List<Finding> findings =
                analyzer(LocalDate.of(2030, 1, 1)).analyze(Fixture.MULTI_MODULE.context());

        assertThat(findings)
                .extracting(
                        f -> f.location().path().orElseThrow(),
                        f -> f.location().line().orElseThrow(),
                        Finding::ruleId)
                .containsExactly(Tuple.tuple("pom.xml", 19, "eol/past-end-of-life"));
    }

    @Test
    void analyze_endOfLifeDateUnreachable_fails() {
        EndOfLifeAnalyzer analyzer =
                new EndOfLifeAnalyzer(
                        loader,
                        new EndOfLifeClient(http(), URI.create(api + "missing/")),
                        clock(REFERENCE_DATE));

        assertThatThrownBy(() -> analyzer.analyze(Fixture.BOOT2_LEGACY.context()))
                .isInstanceOf(IOException.class);
    }

    private static Finding java(List<Finding> findings) {
        return findings.stream()
                .filter(finding -> finding.message().contains("Java"))
                .findFirst()
                .orElseThrow();
    }

    private EndOfLifeAnalyzer analyzer(LocalDate today) {
        return new EndOfLifeAnalyzer(loader, new EndOfLifeClient(http(), api), clock(today));
    }

    private static Clock clock(LocalDate today) {
        return Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    private CachedHttpClient http() {
        return new CachedHttpClient(
                HttpClient.newHttpClient(),
                new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                () -> false,
                1,
                Duration.ZERO);
    }
}
