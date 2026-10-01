package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StealthConfigTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-01");

    @Test
    void apply_severityOverride_changesTheSeverityAndOffDropsTheRule() {
        StealthConfig config =
                config(
                        Map.of(
                                "deps/outdated-major", Optional.of(Severity.LOW),
                                "hygiene/missing-ci", Optional.empty()),
                        List.of(),
                        List.of());

        StealthConfig.Applied applied =
                config.apply(
                        List.of(
                                aFinding("deps/outdated-major", Severity.MEDIUM, "pom.xml"),
                                aFinding("hygiene/missing-ci", Severity.LOW, null)),
                        TODAY);

        assertThat(applied.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.ruleId()).isEqualTo("deps/outdated-major");
                            assertThat(f.severity()).isEqualTo(Severity.LOW);
                        });
    }

    @Test
    void apply_allowedComponentWithoutVersion_dropsAnyVersionOfIt() {
        StealthConfig config =
                config(
                        Map.of(),
                        List.of(),
                        List.of(
                                new StealthConfig.AllowedDependency(
                                        Optional.of(
                                                "pkg:maven/commons-collections/commons-collections"),
                                        Optional.empty(),
                                        Set.of(),
                                        "legacy",
                                        Optional.empty())));

        StealthConfig.Applied applied =
                config.apply(
                        List.of(
                                aDependencyFinding(
                                        "maintenance/no-recent-release",
                                        "pkg:maven/commons-collections/commons-collections@3.2.2",
                                        Optional.empty()),
                                aDependencyFinding(
                                        "deps/outdated-major",
                                        "pkg:maven/commons-collections-extra/x@1",
                                        Optional.empty())),
                        TODAY);

        assertThat(applied.findings())
                .extracting(Finding::ruleId)
                .containsExactly("deps/outdated-major");
    }

    @Test
    void apply_allowedAdvisoryByAlias_dropsThatAdvisoryOnly() {
        StealthConfig config =
                config(
                        Map.of(),
                        List.of(),
                        List.of(
                                new StealthConfig.AllowedDependency(
                                        Optional.empty(),
                                        Optional.of("CVE-2022-42889"),
                                        Set.of(),
                                        "not reachable",
                                        Optional.empty())));
        Finding text4shell =
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        "pkg:maven/org.apache.commons/commons-text@1.9",
                        Optional.of(
                                new Advisory(
                                        "GHSA-599f-7c49-w659",
                                        List.of("CVE-2022-42889"),
                                        OptionalDouble.of(9.8),
                                        Optional.empty())));
        Finding other =
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        "pkg:maven/org.apache.commons/commons-text@1.9",
                        Optional.of(
                                new Advisory(
                                        "GHSA-other",
                                        List.of(),
                                        OptionalDouble.empty(),
                                        Optional.empty())));

        assertThat(config.apply(List.of(text4shell, other), TODAY).findings())
                .containsExactly(other);
    }

    @Test
    void apply_expiredEntry_stopsApplyingAndWarns() {
        StealthConfig config =
                config(
                        Map.of(),
                        List.of(),
                        List.of(
                                new StealthConfig.AllowedDependency(
                                        Optional.of("pkg:maven/a/b"),
                                        Optional.empty(),
                                        Set.of(),
                                        "temporary",
                                        Optional.of(LocalDate.parse("2026-09-30")))));

        StealthConfig.Applied applied =
                config.apply(
                        List.of(
                                aDependencyFinding(
                                        "deps/outdated-major",
                                        "pkg:maven/a/b@1",
                                        Optional.empty())),
                        TODAY);

        assertThat(applied.findings()).hasSize(1);
        assertThat(applied.warnings())
                .containsExactly("allow.dependencies entry expired on 2026-09-30: temporary");
    }

    @Test
    void apply_ignoredPath_dropsFindingsThere() {
        StealthConfig config = config(Map.of(), List.of(PathGlob.of("legacy/**")), List.of());

        StealthConfig.Applied applied =
                config.apply(
                        List.of(
                                aFinding("secrets/jwt", Severity.HIGH, "legacy/App.java"),
                                aFinding("secrets/jwt", Severity.HIGH, "src/App.java")),
                        TODAY);

        assertThat(applied.findings())
                .extracting(f -> f.location().path().orElseThrow())
                .containsExactly("src/App.java");
    }

    private static StealthConfig config(
            Map<String, Optional<Severity>> severity,
            List<PathGlob> ignore,
            List<StealthConfig.AllowedDependency> dependencies) {
        return new StealthConfig(
                Set.of(),
                StealthConfig.Eol.defaults(),
                StealthConfig.Secrets.defaults(),
                ignore,
                severity,
                dependencies,
                StealthConfig.Thresholds.defaults(),
                StealthConfig.FailUnder.none());
    }

    private static Finding aFinding(String ruleId, Severity severity, String path) {
        Location location = path == null ? Location.repository() : Location.file(path, 1);
        return new Finding(
                ruleId,
                Category.TECH,
                severity,
                ruleId,
                location,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of(ruleId, String.valueOf(path)));
    }

    private static Finding aDependencyFinding(
            String ruleId, String purl, Optional<Advisory> advisory) {
        return new Finding(
                ruleId,
                Category.SECURITY,
                Severity.HIGH,
                ruleId,
                Location.file("pom.xml", 10),
                Optional.of(purl),
                advisory,
                Optional.empty(),
                Fingerprints.of(ruleId, purl, advisory.map(Advisory::id).orElse("")));
    }
}
