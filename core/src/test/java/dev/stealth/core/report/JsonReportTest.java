package dev.stealth.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Fixture;
import dev.stealth.core.Schemas;
import dev.stealth.core.StealthConfig.FailUnder;
import dev.stealth.core.score.FailUnderGate;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonReportTest {

    // Runs a real analyzer on the checked-out fixture, so on the Windows CI runner the paths come
    // from a file system with backslash separators
    @Test
    void render_onEveryOs_pathsAreRepoRelativeWithForwardSlashes() throws Exception {
        AnalyzerRunner runner =
                new AnalyzerRunner(
                        List.of(new SecretsAnalyzer(AllAnalyzers.REFERENCE_DATE)),
                        Duration.ofSeconds(60));
        DoctorReport report = runner.run(Fixture.WITH_SECRETS.context());

        String json = JsonReport.render(report, "test", Optional.empty());
        String sarif = SarifReport.render(report, List.of(), "test", Optional.of("pom.xml"));

        List<String> jsonPaths =
                ReportJson.JSON
                        .readTree(json)
                        .get("findings")
                        .valueStream()
                        .map(f -> f.at("/location/path").asString())
                        .toList();
        List<String> sarifPaths =
                ReportJson.JSON
                        .readTree(sarif)
                        .at("/runs/0/results")
                        .valueStream()
                        .map(
                                r ->
                                        r.at("/locations/0/physicalLocation/artifactLocation/uri")
                                                .asString())
                        .toList();
        assertThat(jsonPaths)
                .isNotEmpty()
                .contains("src/main/java/com/example/secrets/GitHubClient.java")
                .allSatisfy(
                        p ->
                                assertThat(p)
                                        .doesNotContain("\\")
                                        .doesNotStartWith("/")
                                        .doesNotContainPattern("^[A-Za-z]:"));
        assertThat(sarifPaths).containsExactlyInAnyOrderElementsOf(jsonPaths);
        assertThat(json).doesNotContain("\r");
        assertThat(sarif).doesNotContain("\r");
    }

    @Test
    void render_failUnderGate_isIncludedWithItsExitCode() {
        DoctorReport report = emptyRun();
        FailUnderGate.Result gate =
                FailUnderGate.check(report.score(), FailUnder.none().withOverall(80));

        JsonNode json =
                ReportJson.JSON.readTree(JsonReport.render(report, "test", Optional.of(gate)));

        assertThat(json.at("/failUnder/passed").asBoolean()).isTrue();
        assertThat(json.at("/failUnder/exitCode").asInt()).isZero();
        assertThat(json.at("/score/overall").asInt()).isEqualTo(100);
    }

    @Test
    void render_filteredRun_hasNullOverallAndNotRunCategory() {
        DoctorReport report =
                new DoctorReport(
                        List.of(),
                        List.of(
                                new AnalyzerResult(
                                        "hygiene",
                                        Category.TECH,
                                        AnalyzerStatus.OK,
                                        Duration.ZERO,
                                        Optional.empty()),
                                new AnalyzerResult(
                                        "vuln",
                                        Category.SECURITY,
                                        AnalyzerStatus.NOT_SELECTED,
                                        Duration.ZERO,
                                        Optional.empty())));

        String rendered = JsonReport.render(report, "test", Optional.empty());
        JsonNode json = ReportJson.JSON.readTree(rendered);

        assertThat(json.at("/score/overall").isNull()).isTrue();
        assertThat(json.at("/score/security/status").asString()).isEqualTo("not_run");
        assertThat(json.at("/score/security/score").isNull()).isTrue();
        assertThat(json.at("/analyzers/1/status").asString()).isEqualTo("not_selected");
        assertThat(Schemas.validate("doctor-report.schema.json", rendered, InputFormat.JSON))
                .isEmpty();
    }

    private static DoctorReport emptyRun() {
        return new DoctorReport(
                List.of(),
                List.of(
                        new AnalyzerResult(
                                "hygiene",
                                Category.TECH,
                                AnalyzerStatus.OK,
                                Duration.ZERO,
                                Optional.empty()),
                        new AnalyzerResult(
                                "vuln",
                                Category.SECURITY,
                                AnalyzerStatus.OK,
                                Duration.ZERO,
                                Optional.empty())));
    }
}
