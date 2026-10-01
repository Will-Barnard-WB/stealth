package dev.stealth.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.networknt.schema.InputFormat;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Fixture;
import dev.stealth.core.Rule;
import dev.stealth.core.Schemas;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.SeededMavenRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON and SARIF for {@code fixtures/boot2-legacy}, every analyzer, recorded remote data,
 * snapshotted. Validated against the published JSON schema and the official SARIF 2.1.0 schema.
 *
 * <p>After an intended change, rerun with {@code -Dstealth.updateSnapshots=true} and review the
 * diff of {@code src/test/resources/snapshots/}.
 */
@WireMockTest
class ReportSnapshotTest {

    static final Path SARIF_SCHEMA = Path.of("src/test/resources/sarif/sarif-schema-2.1.0.json");
    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");

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
    void json_boot2Legacy_matchesTheSnapshotAndTheSchema() throws Exception {
        String json = JsonReport.render(run(), "test", Optional.empty());

        assertThat(Schemas.validate("doctor-report.schema.json", json, InputFormat.JSON)).isEmpty();
        assertMatchesSnapshot(
                "boot2-legacy.json", json.replaceAll("\"durationMs\": \\d+", "\"durationMs\": 0"));
    }

    @Test
    void sarif_boot2Legacy_matchesTheSnapshotAndTheOfficialSchema() throws Exception {
        String sarif = SarifReport.render(run(), rules(), "test", Optional.of("pom.xml"));

        assertThat(Schemas.validate(SARIF_SCHEMA, sarif, InputFormat.JSON)).isEmpty();
        assertMatchesSnapshot("boot2-legacy.sarif", sarif);
    }

    @Test
    void sarif_adrExample_isValidSarif() throws Exception {
        String example = Files.readString(Schemas.docs().resolve("adr/examples/doctor.sarif"));

        assertThat(Schemas.validate(SARIF_SCHEMA, example, InputFormat.JSON)).isEmpty();
    }

    private DoctorReport run() throws InterruptedException {
        return runner.run(Fixture.BOOT2_LEGACY.context());
    }

    private List<Rule> rules() {
        return runner.analyzers().stream().flatMap(a -> a.rules().stream()).toList();
    }

    private static void assertMatchesSnapshot(String name, String actual) throws IOException {
        Path snapshot = SNAPSHOTS.resolve(name);
        if (Boolean.getBoolean("stealth.updateSnapshots") || !Files.exists(snapshot)) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
        }
        // Compared line by line so a CRLF checkout on Windows still matches
        assertThat(actual.lines())
                .containsExactlyElementsOf(Files.readString(snapshot).lines().toList());
    }
}
