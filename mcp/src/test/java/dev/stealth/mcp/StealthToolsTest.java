package dev.stealth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.Advisory;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.score.FixPlanner;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StealthToolsTest {

    @TempDir private Path repo;

    @Test
    void filter_minimumSeverity_keepsThatSeverityAndWorse() {
        Predicate<Finding> filter = StealthTools.filter(null, "high", null);

        assertThat(filter).accepts(finding(Severity.CRITICAL), finding(Severity.HIGH));
        assertThat(filter).rejects(finding(Severity.MEDIUM), finding(Severity.INFO));
    }

    @Test
    void filter_categoryAndAnalyzer_matchCaseInsensitively() {
        assertThat(StealthTools.filter("SECURITY", null, null)).accepts(finding(Severity.LOW));
        assertThat(StealthTools.filter("tech", null, null)).rejects(finding(Severity.LOW));
        assertThat(StealthTools.filter(null, null, "Vuln")).accepts(finding(Severity.LOW));
        assertThat(StealthTools.filter(null, null, "deps")).rejects(finding(Severity.LOW));
    }

    @Test
    void filter_unknownValue_saysWhatsAllowed() {
        assertThatThrownBy(() -> StealthTools.filter("perf", null, null))
                .hasMessage("category must be security or tech, got 'perf'");
        assertThatThrownBy(() -> StealthTools.filter(null, "urgent", null))
                .hasMessage("severity must be critical, high, medium, low or info, got 'urgent'");
    }

    @Test
    void limit_defaultsAndBounds() {
        assertThat(StealthTools.limit(null)).isEqualTo(StealthTools.DEFAULT_LIMIT);
        assertThat(StealthTools.limit(1)).isEqualTo(1);
        assertThatThrownBy(() -> StealthTools.limit(0)).hasMessageContaining("between 1 and 200");
        assertThatThrownBy(() -> StealthTools.limit(201)).hasMessageContaining("between 1 and 200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "relative/repo"})
    void directory_missingOrRelative_isRejected(String path) {
        assertThatThrownBy(() -> StealthTools.directory(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void directory_notADirectory_isRejected() {
        assertThatThrownBy(() -> StealthTools.directory(repo.resolve("missing").toString()))
                .hasMessageStartingWith("not a directory: ");
        assertThat(StealthTools.directory(repo + "/./")).isEqualTo(repo);
    }

    @Test
    void summary_parentWithTransitiveVulnerabilities_namesTheParentUpgrade() {
        Finding outdated =
                new Finding(
                        "deps/outdated-major",
                        Category.TECH,
                        Severity.MEDIUM,
                        "outdated",
                        Location.file("pom.xml", 10),
                        List.of(),
                        Optional.of(
                                "pkg:maven/org.springframework.boot/spring-boot-starter-parent@2.7.18"),
                        Optional.empty(),
                        Optional.of(new Remediation(Optional.of("4.1.1"), Optional.empty())),
                        Fingerprints.of("deps/outdated-major", "parent"));
        Finding tomcat =
                vulnerability("org.apache.tomcat.embed/tomcat-embed-core@9.0.83", "9.0.121");

        String summary =
                StealthTools.summary(
                        new FixPlanner.Fix(
                                Location.file("pom.xml", 10), List.of(outdated, tomcat), 18));

        assertThat(summary)
                .isEqualTo(
                        "Upgrade org.springframework.boot:spring-boot-starter-parent from 2.7.18 to"
                            + " 4.1.1: 1 known vulnerability in tomcat-embed-core (check they're"
                            + " fixed after upgrading)");
    }

    @Test
    void summary_vulnerableDirectDependency_namesTheFixingVersion() {
        Finding text = vulnerability("org.apache.commons/commons-text@1.9", "1.10.0");

        String summary =
                StealthTools.summary(
                        new FixPlanner.Fix(Location.file("pom.xml", 40), List.of(text), 5));

        assertThat(summary)
                .isEqualTo(
                        "Upgrade org.apache.commons:commons-text from 1.9 to 1.10.0: 1 known"
                                + " vulnerability in commons-text");
    }

    @Test
    void verdict_cleanLatest_saysSo() {
        DependencyCheck.Result result =
                new DependencyCheck.Result(
                        "org.example",
                        "lib",
                        true,
                        Optional.of("2.0"),
                        Optional.of("2.0"),
                        Optional.of(LocalDate.parse("2026-01-01")),
                        Optional.of(true),
                        List.of(),
                        Optional.empty());

        assertThat(StealthTools.verdict(result)).isEqualTo("2.0 has no known vulnerabilities.");
    }

    @Test
    void verdict_unmaintained_suggestsAnAlternative() {
        DependencyCheck.Result result =
                new DependencyCheck.Result(
                        "commons-collections",
                        "commons-collections",
                        true,
                        Optional.of("3.2.2"),
                        Optional.of("3.2.2"),
                        Optional.of(LocalDate.parse("2015-11-12")),
                        Optional.of(false),
                        List.of(),
                        Optional.empty());

        assertThat(StealthTools.verdict(result))
                .endsWith(
                        "It looks unmaintained: no release since 2015-11-12. Consider a maintained"
                                + " alternative.");
    }

    @Test
    void verdict_notOnCentral_saysSo() {
        DependencyCheck.Result result =
                new DependencyCheck.Result(
                        "com.example",
                        "internal",
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        Optional.empty());

        assertThat(StealthTools.verdict(result))
                .startsWith("com.example:internal isn't on Maven Central");
    }

    @Test
    void where_repositoryLevel_saysWholeRepository() {
        assertThat(StealthTools.where(Location.repository())).isEqualTo("(whole repository)");
        assertThat(StealthTools.where(Location.file("pom.xml", 3))).isEqualTo("pom.xml:3");
    }

    private static Finding finding(Severity severity) {
        return new Finding(
                "vuln/known-vulnerability",
                Category.SECURITY,
                severity,
                "m",
                Location.file("pom.xml", 1),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of("vuln/known-vulnerability", severity.name()));
    }

    private static Finding vulnerability(String coordinates, String fixedIn) {
        return new Finding(
                "vuln/known-vulnerability",
                Category.SECURITY,
                Severity.CRITICAL,
                "vulnerable",
                Location.file("pom.xml", 10),
                List.of(),
                Optional.of("pkg:maven/" + coordinates),
                Optional.of(
                        new Advisory(
                                "GHSA-x", List.of(), OptionalDouble.empty(), Optional.empty())),
                Optional.of(new Remediation(Optional.of(fixedIn), Optional.empty())),
                Fingerprints.of("vuln/known-vulnerability", coordinates));
    }
}
