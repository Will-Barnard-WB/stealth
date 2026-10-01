package dev.stealth.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import dev.stealth.core.Advisory;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Rule;
import dev.stealth.core.Schemas;
import dev.stealth.core.Severity;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class SarifReportTest {

    private static final Rule KNOWN_VULNERABILITY =
            new Rule(
                    "vuln/known-vulnerability",
                    "Known vulnerability",
                    "A dependency has a known vulnerability published in OSV.dev.",
                    URI.create("https://example.com/vuln"),
                    Category.SECURITY,
                    Severity.HIGH);
    private static final Rule MISSING_CODEOWNERS =
            new Rule(
                    "hygiene/missing-codeowners",
                    "No CODEOWNERS",
                    "The repository has no CODEOWNERS file.",
                    URI.create("https://example.com/codeowners"),
                    Category.TECH,
                    Severity.LOW);

    @Test
    void render_advisoryFinding_getsARulePerAdvisoryWithItsCvssScore() {
        Finding text4shell =
                vulnerability("GHSA-599f-7c49-w659", OptionalDouble.of(9.8), Severity.CRITICAL);

        JsonNode run = render(List.of(text4shell), Optional.of("pom.xml"));

        JsonNode rule = run.at("/tool/driver/rules/0");
        assertThat(rule.get("id").asString())
                .isEqualTo("vuln/known-vulnerability/GHSA-599f-7c49-w659");
        assertThat(rule.get("name").asString()).isEqualTo("KnownVulnerability");
        assertThat(rule.at("/properties/security-severity").asString()).isEqualTo("9.8");
        assertThat(rule.at("/properties/stealthRuleId").asString())
                .isEqualTo("vuln/known-vulnerability");
        assertThat(rule.get("helpUri").asString())
                .isEqualTo("https://osv.dev/vulnerability/GHSA-599f-7c49-w659");
        JsonNode result = run.at("/results/0");
        assertThat(result.get("ruleIndex").asInt()).isZero();
        assertThat(result.get("level").asString()).isEqualTo("error");
        assertThat(result.at("/partialFingerprints/stealth~1v1").asString())
                .isEqualTo(text4shell.fingerprint());
        assertThat(result.at("/properties/fixedVersion").asString()).isEqualTo("1.10.0");
        assertThat(result.at("/properties/aliases/0").asString()).isEqualTo("CVE-2022-42889");
    }

    @Test
    void render_advisoryWithoutCvss_usesTheSeverityBand() {
        JsonNode run =
                render(
                        List.of(vulnerability("GHSA-x", OptionalDouble.empty(), Severity.MEDIUM)),
                        Optional.empty());

        assertThat(run.at("/tool/driver/rules/0/properties/security-severity").asString())
                .isEqualTo("5.5");
    }

    @Test
    void render_repositoryLevelFinding_isAnchoredToTheRootPomLine1() {
        JsonNode location =
                render(List.of(missingCodeowners()), Optional.of("pom.xml"))
                        .at("/results/0/locations/0");

        assertThat(location.at("/physicalLocation/artifactLocation/uri").asString())
                .isEqualTo("pom.xml");
        assertThat(location.at("/physicalLocation/artifactLocation/uriBaseId").asString())
                .isEqualTo("%SRCROOT%");
        assertThat(location.at("/physicalLocation/region/startLine").asInt()).isEqualTo(1);
        assertThat(location.at("/logicalLocations/0/kind").asString()).isEqualTo("repository");
    }

    @Test
    void render_repositoryLevelFindingWithoutAPom_hasOnlyALogicalLocation() {
        String sarif =
                SarifReport.render(
                        report(List.of(missingCodeowners())),
                        List.of(MISSING_CODEOWNERS),
                        "test",
                        Optional.empty());

        JsonNode location = ReportJson.JSON.readTree(sarif).at("/runs/0/results/0/locations/0");
        assertThat(location.has("physicalLocation")).isFalse();
        assertThat(Schemas.validate(ReportSnapshotTest.SARIF_SCHEMA, sarif, InputFormat.JSON))
                .isEmpty();
    }

    @Test
    void render_techRule_hasNoSecuritySeverityAndOnlyUsedRulesAreListed() {
        JsonNode run = render(List.of(missingCodeowners()), Optional.of("pom.xml"));

        assertThat(run.at("/tool/driver/rules")).hasSize(1);
        assertThat(run.at("/tool/driver/rules/0/properties").has("security-severity")).isFalse();
        assertThat(run.at("/tool/driver/rules/0/properties/tags/0").asString())
                .isEqualTo("maintainability");
        assertThat(run.at("/results/0/level").asString()).isEqualTo("note");
    }

    @Test
    void render_relatedLocations_areListedWithIds() {
        Finding duplicated =
                new Finding(
                        "duplication/duplicated-code",
                        Category.TECH,
                        Severity.LOW,
                        "duplicated",
                        new Location(
                                Optional.of("a/src/main/java/A.java"),
                                OptionalInt.of(10),
                                Optional.of("a")),
                        List.of(Location.file("b/src/main/java/B.java", 20)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Fingerprints.of("duplication/duplicated-code", "x"));

        JsonNode result = render(List.of(duplicated), Optional.of("pom.xml")).at("/results/0");

        assertThat(result.at("/locations/0/logicalLocations/0/name").asString()).isEqualTo("a");
        assertThat(result.at("/relatedLocations/0/id").asInt()).isEqualTo(1);
        assertThat(
                        result.at("/relatedLocations/0/physicalLocation/artifactLocation/uri")
                                .asString())
                .isEqualTo("b/src/main/java/B.java");
        assertThat(result.at("/relatedLocations/0/physicalLocation/region/startLine").asInt())
                .isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL, error", "HIGH, error", "MEDIUM, warning", "LOW, note", "INFO, note"})
    void level_mapsSeverityAsAdr0003Says(Severity severity, String level) {
        assertThat(SarifReport.level(severity)).isEqualTo(level);
    }

    @Test
    void render_runProperties_carryTheScoreAndAutomationId() {
        JsonNode run = render(List.of(missingCodeowners()), Optional.of("pom.xml"));

        assertThat(run.at("/automationDetails/id").asString()).isEqualTo("stealth/doctor/");
        assertThat(run.at("/properties/stealthScore/tech").asInt()).isEqualTo(99);
        assertThat(run.at("/properties/stealthScore/scoringVersion").asInt()).isEqualTo(1);
        assertThat(run.at("/tool/driver/semanticVersion").asString()).isEqualTo("test");
    }

    private static JsonNode render(List<Finding> findings, Optional<String> buildFile) {
        String sarif =
                SarifReport.render(
                        report(findings),
                        List.of(KNOWN_VULNERABILITY, MISSING_CODEOWNERS),
                        "test",
                        buildFile);
        assertThat(Schemas.validate(ReportSnapshotTest.SARIF_SCHEMA, sarif, InputFormat.JSON))
                .isEmpty();
        return ReportJson.JSON.readTree(sarif).at("/runs/0");
    }

    private static DoctorReport report(List<Finding> findings) {
        return new DoctorReport(
                findings,
                List.of(
                        new AnalyzerResult(
                                "vuln",
                                Category.SECURITY,
                                AnalyzerStatus.OK,
                                Duration.ZERO,
                                Optional.empty()),
                        new AnalyzerResult(
                                "hygiene",
                                Category.TECH,
                                AnalyzerStatus.OK,
                                Duration.ZERO,
                                Optional.empty())));
    }

    private static Finding vulnerability(String id, OptionalDouble cvss, Severity severity) {
        String purl = "pkg:maven/org.apache.commons/commons-text@1.9";
        return new Finding(
                "vuln/known-vulnerability",
                Category.SECURITY,
                severity,
                "org.apache.commons:commons-text 1.9: "
                        + id
                        + " (CVE-2022-42889) Arbitrary code execution; fixed in 1.10.0",
                Location.file("pom.xml", 42),
                List.of(),
                Optional.of(purl),
                Optional.of(
                        new Advisory(
                                id,
                                List.of("CVE-2022-42889"),
                                cvss,
                                Optional.of(URI.create("https://osv.dev/vulnerability/" + id)))),
                Optional.of(new Remediation(Optional.of("1.10.0"), Optional.empty())),
                Fingerprints.of("vuln/known-vulnerability", purl, id));
    }

    private static Finding missingCodeowners() {
        return new Finding(
                "hygiene/missing-codeowners",
                Category.TECH,
                Severity.LOW,
                "No CODEOWNERS file",
                Location.repository(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of("hygiene/missing-codeowners"));
    }
}
