package dev.stealth.core.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Fixture;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.SeededMavenRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every analyzer on the fixtures, with recorded remote data pinned to the reference date, and the
 * resulting scores snapshotted. A change here is a scoring change: bump {@code scoringVersion} or
 * explain why the fixtures' findings changed.
 */
@WireMockTest
class ScoreGoldenTest {

    @TempDir static Path seededRepository;

    private static MavenModelLoader loader;

    @TempDir private Path cacheDirectory;

    private AnalyzerRunner runner;

    @BeforeAll
    static void createLoader() {
        loader = new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
    }

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        runner = AllAnalyzers.runner(wireMock.getHttpBaseUrl(), cacheDirectory, loader);
    }

    @Test
    void score_boot4Clean_scoresAtLeast95InBothCategories() throws Exception {
        DoctorReport report = runner.run(Fixture.BOOT4_CLEAN.context());
        HealthScore score = report.score();

        assertThat(report.results())
                .as(() -> AllAnalyzers.failures(report))
                .allMatch(r -> r.status() == AnalyzerStatus.OK);
        assertThat(score.security().score()).isGreaterThanOrEqualTo(95);
        assertThat(score.tech().score()).isGreaterThanOrEqualTo(95);
        assertThat(score.overall()).hasValue(100);
    }

    @Test
    void score_boot2Legacy_matchesTheSnapshot() throws Exception {
        DoctorReport report = runner.run(Fixture.BOOT2_LEGACY.context());
        HealthScore score = report.score();

        assertThat(report.results())
                .as(() -> AllAnalyzers.failures(report))
                .extracting(AnalyzerResult::status)
                .containsOnly(AnalyzerStatus.OK);
        assertThat(score.security().score()).isEqualTo(SNAPSHOT_SECURITY);
        assertThat(score.tech().score()).isEqualTo(SNAPSHOT_TECH);
        assertThat(score.overall()).hasValue(SNAPSHOT_OVERALL);
        assertThat(report.fixes().getFirst().location().line()).hasValue(10);
    }

    @Test
    void score_boot2Legacy_isLowerThanBoot4CleanInBothCategories() throws Exception {
        HealthScore legacy = runner.run(Fixture.BOOT2_LEGACY.context()).score();
        HealthScore clean = runner.run(Fixture.BOOT4_CLEAN.context()).score();

        assertThat(legacy.security().score()).isLessThan(clean.security().score());
        assertThat(legacy.tech().score()).isLessThan(clean.tech().score());
    }

    // Security: vuln 40 (104 findings, capped) + eol 8 = 52, then the critical ceiling: 50.
    // Tech: deps 6.2 + hygiene 1.7 + maintenance 1.0 = 8.9 off: 91. Overall: critical ceiling.
    private static final int SNAPSHOT_SECURITY = 50;
    private static final int SNAPSHOT_TECH = 91;
    private static final int SNAPSHOT_OVERALL = 50;
}
