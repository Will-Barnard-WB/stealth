package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Advisory;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class TerminalReportTest {

    private static final Path ROOT = Path.of("/work/repo");
    private static final Location PARENT = Location.file("pom.xml", 10);
    private static final Location TEXT = Location.file("pom.xml", 40);
    private static final Location GUAVA = Location.file("pom.xml", 35);

    private final TerminalReport report = new TerminalReport(Ansi.OFF, true);

    @Test
    void render_findingsAtTheSameLine_groupsThemIntoOneFixRankedByWorstSeverity() {
        String out =
                render(
                        false,
                        anOutdated(
                                GUAVA,
                                "com.google.guava",
                                "guava",
                                "30.1-jre",
                                "33.7.2-jre",
                                Severity.MEDIUM),
                        aVulnerability(
                                GUAVA,
                                "com.google.guava:guava",
                                "30.1-jre",
                                "GHSA-a",
                                Severity.LOW,
                                "",
                                "32.0.0-jre"),
                        anOutdated(
                                TEXT,
                                "org.apache.commons",
                                "commons-text",
                                "1.9",
                                "1.15.0",
                                Severity.LOW),
                        aVulnerability(
                                TEXT,
                                "org.apache.commons:commons-text",
                                "1.9",
                                "GHSA-599f-7c49-w659",
                                Severity.CRITICAL,
                                "",
                                "1.10.0"));

        assertThat(out)
                .contains("1  pom.xml:40   commons-text  1.9 → 1.15.0")
                .contains("CRITICAL  CVE-GHSA-599f-7c49-w659  Text4Shell")
                .contains("2  pom.xml:35   guava  30.1-jre → 33.7.2-jre")
                .contains("4 findings in 2 fixes");
    }

    @Test
    void render_transitiveVulnerabilities_summariseTheComponentsBehindTheFix() {
        List<Finding> findings = new ArrayList<>();
        findings.add(
                anOutdated(
                        PARENT,
                        "org.springframework.boot",
                        "spring-boot-starter-parent",
                        "2.7.18",
                        "4.1.1",
                        Severity.MEDIUM));
        for (int i = 0; i < 3; i++) {
            findings.add(
                    aVulnerability(
                            PARENT,
                            "org.apache.tomcat.embed:tomcat-embed-core",
                            "9.0.83",
                            "GHSA-t" + i,
                            Severity.HIGH,
                            "; via spring-boot-starter-web > spring-boot-starter-tomcat",
                            "9.0.121"));
        }
        findings.add(
                aVulnerability(
                        PARENT,
                        "org.yaml:snakeyaml",
                        "1.30",
                        "GHSA-s",
                        Severity.CRITICAL,
                        "; via spring-boot-starter-web",
                        "2.0"));

        String out = render(false, findings.toArray(Finding[]::new));

        assertThat(out)
                .contains("spring-boot-starter-parent  2.7.18 → 4.1.1")
                .contains("4 vulnerabilities come in through it: 1 critical · 3 high")
                .contains("tomcat-embed-core 3, snakeyaml 1");
    }

    @Test
    void render_unmaintainedDependency_namesItAndSaysWhy() {
        Finding unmaintained =
                new Finding(
                        "maintenance/no-recent-release",
                        Category.TECH,
                        Severity.LOW,
                        "commons-collections:commons-collections: no release in 10 years"
                                + " (newest is 3.2.2, released 2015-11-12)",
                        Location.file("pom.xml", 45),
                        Optional.of("pkg:maven/commons-collections/commons-collections@3.2.2"),
                        Optional.empty(),
                        Optional.of(new Remediation(Optional.empty(), Optional.empty())),
                        Fingerprints.of("maintenance/no-recent-release", "commons-collections"));

        String out = render(false, unmaintained);

        // Compare whole lines: the report ends lines with the platform's separator
        assertThat(out.lines()).contains("   1  pom.xml:45   commons-collections  3.2.2");
        assertThat(out)
                .contains("no release in 10 years (newest is 3.2.2, released 2015-11-12)")
                .doesNotContain("commons-collections:commons-collections: no release");
    }

    @Test
    void render_repositoryFindings_areSeparateFixes() {
        Finding codeowners = aRepositoryFinding("hygiene/missing-codeowners", "No CODEOWNERS file");
        Finding ci = aRepositoryFinding("hygiene/missing-ci", "No CI configuration");

        String out = render(false, codeowners, ci);

        assertThat(out)
                .contains("No CODEOWNERS file", "No CI configuration", "2 findings in 2 fixes");
    }

    @Test
    void render_criticalFinding_showsTheCappedScoresAndGrades() {
        String out =
                render(
                        false,
                        aVulnerability(
                                TEXT,
                                "org.apache.commons:commons-text",
                                "1.9",
                                "GHSA-x",
                                Severity.CRITICAL,
                                "",
                                "1.10.0"));

        assertThat(out.lines())
                .contains(
                        "  Health     50  D   capped at 50 by critical findings",
                        "  Security   50  D   1 critical",
                        "  Tech       100 A");
    }

    @Test
    void render_severityFilter_narrowsTheListButNotTheScore() {
        String out =
                report.render(
                        ROOT,
                        new DoctorReport(
                                List.of(
                                        aVulnerability(
                                                TEXT,
                                                "org.apache.commons:commons-text",
                                                "1.9",
                                                "GHSA-x",
                                                Severity.CRITICAL,
                                                "",
                                                "1.10.0"),
                                        anOutdated(
                                                GUAVA,
                                                "com.google.guava",
                                                "guava",
                                                "30.1-jre",
                                                "33.7.2-jre",
                                                Severity.LOW)),
                                List.of(
                                        aResult("deps", AnalyzerStatus.OK, null),
                                        aResult("vuln", AnalyzerStatus.OK, null))),
                        false,
                        Set.of(Severity.LOW));

        assertThat(out.lines()).contains("  Health     50  D   capped at 50 by critical findings");
        assertThat(out).contains("guava").doesNotContain("commons-text");
    }

    @Test
    void render_filteredRun_marksThePartialCategoryAndHidesFilteredAnalyzers() {
        String out =
                report.render(
                        ROOT,
                        new DoctorReport(
                                List.of(
                                        anOutdated(
                                                GUAVA,
                                                "com.google.guava",
                                                "guava",
                                                "30.1-jre",
                                                "33.7.2-jre",
                                                Severity.LOW)),
                                List.of(
                                        aResult("deps", AnalyzerStatus.OK, null),
                                        aResult("duplication", AnalyzerStatus.NOT_SELECTED, null),
                                        aResult("vuln", AnalyzerStatus.NOT_SELECTED, null))),
                        false);

        assertThat(out.lines())
                .contains(
                        "  ✓ deps   0.3 s",
                        "  Health     -    needs a run of every analyzer; some were left out",
                        "  Security   not run",
                        "  Tech       99  A   1 low   partial: only deps ran");
    }

    @Test
    void render_moreThanTopFixes_showsTheTopTenAndPointsAtAll() {
        Finding[] findings = new Finding[12];
        for (int i = 0; i < findings.length; i++) {
            findings[i] =
                    anOutdated(
                            Location.file("pom.xml", 100 + i),
                            "org.example",
                            "lib" + i,
                            "1.0",
                            "1.1",
                            Severity.INFO);
        }

        String out = render(false, findings);

        assertThat(out)
                .contains("10  pom.xml:109")
                .doesNotContain("pom.xml:110", "pom.xml:111")
                .contains(
                        "12 findings in 12 fixes · showing the top 10 · stealth doctor --all lists"
                                + " every finding");
    }

    @Test
    void render_all_listsEveryFixAndFinding() {
        Finding[] findings = new Finding[12];
        for (int i = 0; i < findings.length; i++) {
            findings[i] =
                    anOutdated(
                            Location.file("pom.xml", 100 + i),
                            "org.example",
                            "lib" + i,
                            "1.0",
                            "1.1",
                            Severity.INFO);
        }

        String out = render(true, findings);

        assertThat(out)
                .contains("12  pom.xml:111", "org.example:lib11 1.0 is outdated")
                .contains("12 findings in 12 fixes")
                .doesNotContain("--all");
    }

    @Test
    void render_failedAnalyzer_saysSoAndDoesNotClaimAllClear() {
        DoctorReport doctor =
                new DoctorReport(
                        List.of(),
                        List.of(
                                aResult("deps", AnalyzerStatus.OK, null),
                                aResult("vuln", AnalyzerStatus.FAILED, "OSV.dev unreachable")));

        String out = report.render(ROOT, doctor, false);

        assertThat(out)
                .contains("✓ deps", "✗ vuln")
                .contains("vuln failed: OSV.dev unreachable")
                .contains("No problems found by the analyzers that finished.");
        assertThat(out.lines()).doesNotContain("  No problems found.");
    }

    @Test
    void render_noFindings_saysNoProblemsFound() {
        String out =
                report.render(
                        ROOT,
                        new DoctorReport(
                                List.of(), List.of(aResult("deps", AnalyzerStatus.OK, null))),
                        false);

        assertThat(out).contains("No problems found.");
    }

    @Test
    void render_withoutUnicode_usesPlainSymbols() {
        TerminalReport plain = new TerminalReport(Ansi.OFF, false);

        String out =
                plain.render(
                        ROOT,
                        new DoctorReport(
                                List.of(
                                        anOutdated(
                                                TEXT,
                                                "org.apache.commons",
                                                "commons-text",
                                                "1.9",
                                                "1.15.0",
                                                Severity.LOW)),
                                List.of(aResult("deps", AnalyzerStatus.OK, null))),
                        false);

        assertThat(out)
                .contains("ok deps", "commons-text  1.9 -> 1.15.0")
                .doesNotContain("✓", "→", "·");
    }

    @Test
    void render_ansi_coloursSeverities() {
        TerminalReport colour = new TerminalReport(Ansi.ON, true);

        String out =
                colour.render(
                        ROOT,
                        new DoctorReport(
                                List.of(
                                        aVulnerability(
                                                TEXT,
                                                "org.apache.commons:commons-text",
                                                "1.9",
                                                "GHSA-x",
                                                Severity.CRITICAL,
                                                "",
                                                "1.10.0")),
                                List.of(aResult("vuln", AnalyzerStatus.OK, null))),
                        false);

        assertThat(out).contains("\u001B[38;5;196m");
    }

    private String render(boolean all, Finding... findings) {
        return report.render(
                ROOT,
                new DoctorReport(
                        List.of(findings),
                        List.of(
                                aResult("deps", AnalyzerStatus.OK, null),
                                aResult("vuln", AnalyzerStatus.OK, null))),
                all);
    }

    private static Finding anOutdated(
            Location location,
            String groupId,
            String artifactId,
            String version,
            String latest,
            Severity severity) {
        return new Finding(
                "deps/outdated-minor",
                Category.TECH,
                severity,
                groupId
                        + ":"
                        + artifactId
                        + " "
                        + version
                        + " is outdated: "
                        + latest
                        + " is available",
                location,
                Optional.of("pkg:maven/" + groupId + "/" + artifactId + "@" + version),
                Optional.empty(),
                Optional.of(new Remediation(Optional.of(latest), Optional.empty())),
                Fingerprints.of("deps/outdated-minor", artifactId, location.toString()));
    }

    private static Finding aVulnerability(
            Location location,
            String name,
            String version,
            String id,
            Severity severity,
            String via,
            String fixed) {
        String cve = "CVE-" + id;
        return new Finding(
                "vuln/known-vulnerability",
                Category.SECURITY,
                severity,
                name
                        + " "
                        + version
                        + ": "
                        + id
                        + " ("
                        + cve
                        + ") Text4Shell; fixed in "
                        + fixed
                        + via,
                location,
                Optional.of("pkg:maven/" + name.replace(':', '/') + "@" + version),
                Optional.of(
                        new Advisory(id, List.of(cve), OptionalDouble.empty(), Optional.empty())),
                Optional.of(new Remediation(Optional.of(fixed), Optional.empty())),
                Fingerprints.of("vuln/known-vulnerability", name, id, location.toString()));
    }

    private static Finding aRepositoryFinding(String ruleId, String message) {
        return new Finding(
                ruleId,
                Category.TECH,
                Severity.LOW,
                message,
                Location.repository(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of(ruleId, ""));
    }

    private static AnalyzerResult aResult(String id, AnalyzerStatus status, String error) {
        Category category =
                id.equals("vuln") || id.equals("secrets") ? Category.SECURITY : Category.TECH;
        return new AnalyzerResult(
                id, category, status, Duration.ofMillis(300), Optional.ofNullable(error));
    }
}
