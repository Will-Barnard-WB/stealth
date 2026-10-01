package dev.stealth.core.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import dev.stealth.core.Advisory;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FixPlannerTest {

    private static int sequence;

    private static final Location PARENT = Location.file("pom.xml", 10);
    private static final Location TEXT = Location.file("pom.xml", 40);
    private static final Location GUAVA = Location.file("pom.xml", 35);

    @Test
    void plan_parentUpgrade_groupsItsOwnAndManagedFindingsIntoOneFix() {
        List<Finding> findings =
                List.of(
                        aDependencyFinding(
                                "deps/outdated-major",
                                Category.TECH,
                                Severity.MEDIUM,
                                PARENT,
                                "spring-boot-starter-parent"),
                        aDependencyFinding(
                                "vuln/known-vulnerability",
                                Category.SECURITY,
                                Severity.HIGH,
                                PARENT,
                                "tomcat-embed-core"),
                        aDependencyFinding(
                                "vuln/known-vulnerability",
                                Category.SECURITY,
                                Severity.CRITICAL,
                                PARENT,
                                "snakeyaml"),
                        aDependencyFinding(
                                "deps/outdated-minor",
                                Category.TECH,
                                Severity.LOW,
                                GUAVA,
                                "guava"));

        List<FixPlanner.Fix> fixes = FixPlanner.plan(findings);

        assertThat(fixes).hasSize(2);
        assertThat(fixes.getFirst().location()).isEqualTo(PARENT);
        assertThat(fixes.getFirst().findings()).hasSize(3);
        assertThat(fixes.getFirst().findings().getFirst().severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void plan_findingsThatAreNotAboutADependency_areSeparateFixesEvenAtOneLocation() {
        List<Finding> findings =
                List.of(
                        aRepositoryFinding("hygiene/missing-codeowners"),
                        aRepositoryFinding("hygiene/missing-ci"));

        assertThat(FixPlanner.plan(findings)).hasSize(2);
    }

    @Test
    void plan_sameWorstSeverity_ranksByPointsGained() {
        List<Finding> findings = new ArrayList<>();
        // One high vulnerability on its own vs. two highs behind one upgrade
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.HIGH,
                        GUAVA,
                        "guava"));
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.HIGH,
                        TEXT,
                        "commons-text"));
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.HIGH,
                        TEXT,
                        "commons-text"));

        List<FixPlanner.Fix> fixes = FixPlanner.plan(findings);

        assertThat(fixes).extracting(FixPlanner.Fix::location).containsExactly(TEXT, GUAVA);
        assertThat(fixes.get(0).points()).isGreaterThan(fixes.get(1).points());
    }

    @Test
    void plan_whileACriticalPinsTheScore_stillRanksTheOtherFixesByWhatTheyAreWorth() {
        List<Finding> findings = new ArrayList<>();
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.CRITICAL,
                        PARENT,
                        "snakeyaml"));
        // Same worst severity; guava's fix is worth more (two vulnerabilities, a major update)
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.MEDIUM,
                        TEXT,
                        "commons-lang3"));
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.MEDIUM,
                        GUAVA,
                        "guava"));
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.LOW,
                        GUAVA,
                        "guava"));
        findings.add(
                aDependencyFinding(
                        "deps/outdated-major", Category.TECH, Severity.MEDIUM, GUAVA, "guava"));

        List<FixPlanner.Fix> fixes = FixPlanner.plan(findings);

        assertThat(fixes).extracting(FixPlanner.Fix::location).containsExactly(PARENT, GUAVA, TEXT);
    }

    @Test
    void plan_criticalFix_comesFirstEvenIfWorthFewerPoints() {
        List<Finding> findings = new ArrayList<>();
        findings.add(
                aDependencyFinding(
                        "vuln/known-vulnerability",
                        Category.SECURITY,
                        Severity.CRITICAL,
                        TEXT,
                        "commons-text"));
        for (int i = 0; i < 5; i++) {
            findings.add(
                    aDependencyFinding(
                            "vuln/known-vulnerability",
                            Category.SECURITY,
                            Severity.HIGH,
                            PARENT,
                            "tomcat" + i));
        }

        assertThat(FixPlanner.plan(findings).getFirst().location()).isEqualTo(TEXT);
    }

    @Test
    void plan_pointsGained_matchTheScoreWithoutThatFix() {
        List<Finding> findings =
                List.of(
                        aDependencyFinding(
                                "deps/outdated-major",
                                Category.TECH,
                                Severity.MEDIUM,
                                GUAVA,
                                "guava"),
                        aDependencyFinding(
                                "vuln/known-vulnerability",
                                Category.SECURITY,
                                Severity.HIGH,
                                GUAVA,
                                "guava"));

        FixPlanner.Fix only = FixPlanner.plan(findings).getFirst();

        // Without it, everything is 100: 0.6 × (100 − 8) + 0.4 × (100 − 3) = 94
        assertThat(only.points()).isCloseTo(6.0, offset(1e-9));
    }

    @Test
    void plan_sameFindingsInAnyOrder_givesTheSameOrder() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            findings.add(
                    aDependencyFinding(
                            "deps/outdated-minor",
                            Category.TECH,
                            Severity.LOW,
                            Location.file("pom.xml", 100 + i),
                            "lib" + i));
        }
        findings.add(aRepositoryFinding("hygiene/missing-ci"));
        findings.add(aRepositoryFinding("hygiene/missing-codeowners"));
        List<FixPlanner.Fix> expected = FixPlanner.plan(findings);

        for (long seed = 0; seed < 20; seed++) {
            List<Finding> shuffled = new ArrayList<>(findings);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(FixPlanner.plan(shuffled)).isEqualTo(expected);
        }
    }

    @Test
    void only_severities_keepsMatchingFindingsAndDropsEmptyFixes() {
        FixPlanner.Fix fix =
                FixPlanner.plan(
                                List.of(
                                        aDependencyFinding(
                                                "deps/outdated-major",
                                                Category.TECH,
                                                Severity.MEDIUM,
                                                GUAVA,
                                                "guava"),
                                        aDependencyFinding(
                                                "vuln/known-vulnerability",
                                                Category.SECURITY,
                                                Severity.HIGH,
                                                GUAVA,
                                                "guava")))
                        .getFirst();

        assertThat(fix.only(Set.of(Severity.HIGH)).orElseThrow().findings()).hasSize(1);
        assertThat(fix.only(Set.of(Severity.CRITICAL))).isEmpty();
    }

    private static Finding aDependencyFinding(
            String ruleId,
            Category category,
            Severity severity,
            Location location,
            String artifact) {
        boolean vulnerability = ruleId.startsWith("vuln/");
        return new Finding(
                ruleId,
                category,
                severity,
                artifact + " " + ruleId,
                location,
                Optional.of("pkg:maven/org.example/" + artifact + "@1.0"),
                vulnerability
                        ? Optional.of(
                                new Advisory(
                                        "GHSA-" + artifact + severity,
                                        List.of(),
                                        OptionalDouble.empty(),
                                        Optional.empty()))
                        : Optional.empty(),
                Optional.of(new Remediation(Optional.of("2.0"), Optional.empty())),
                Fingerprints.of(
                        ruleId,
                        artifact,
                        severity.name(),
                        location.toString(),
                        String.valueOf(sequence++)));
    }

    private static Finding aRepositoryFinding(String ruleId) {
        return new Finding(
                ruleId,
                Category.TECH,
                Severity.LOW,
                ruleId,
                Location.repository(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of(ruleId, ""));
    }
}
