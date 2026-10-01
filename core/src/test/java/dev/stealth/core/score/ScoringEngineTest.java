package dev.stealth.core.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Severity;
import dev.stealth.core.score.HealthScore.Deduction;
import dev.stealth.core.score.HealthScore.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoringEngineTest {

    private static final List<AnalyzerResult> BOTH_RAN =
            List.of(
                    aResult("deps", Category.TECH, AnalyzerStatus.OK),
                    aResult("vuln", Category.SECURITY, AnalyzerStatus.OK));

    // ADR-0002's worked example
    @Test
    void score_adrWorkedExample_gives72Tech77Security75Overall() {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(some(12, "deps", Category.TECH, Severity.MEDIUM));
        findings.addAll(some(6, "deps", Category.TECH, Severity.LOW));
        findings.addAll(some(4, "duplication", Category.TECH, Severity.MEDIUM));
        findings.addAll(some(2, "hygiene", Category.TECH, Severity.LOW));
        findings.addAll(some(2, "vuln", Category.SECURITY, Severity.HIGH));
        findings.addAll(some(1, "vuln", Category.SECURITY, Severity.MEDIUM));
        findings.addAll(some(1, "secrets", Category.SECURITY, Severity.HIGH));

        HealthScore score = ScoringEngine.score(findings, BOTH_RAN);

        assertThat(score.tech().score()).isEqualTo(72);
        assertThat(score.security().score()).isEqualTo(77);
        assertThat(score.overall()).hasValue(75);
        assertThat(score.tech().deductions())
                .extracting(Deduction::analyzerId, d -> round1(d.points()))
                .containsExactly(
                        tuple("deps", 18.4), tuple("duplication", 8.4), tuple("hygiene", 1.7));
        assertThat(score.security().deductions())
                .extracting(Deduction::analyzerId, d -> round1(d.points()))
                .containsExactly(tuple("vuln", 15.4), tuple("secrets", 8.0));
    }

    // ADR-0002's reference points for one analyzer
    @ParameterizedTest
    @CsvSource({
        "1, MEDIUM, 3.0",
        "10, MEDIUM, 15.1",
        "50, MEDIUM, 38.3",
        "200, MEDIUM, 40.0",
        "1, CRITICAL, 15.0",
        "3, CRITICAL, 34.3"
    })
    void score_oneAnalyzer_deductsWithDiminishingReturns(
            int count, Severity severity, double expected) {
        HealthScore score =
                ScoringEngine.score(some(count, "deps", Category.TECH, severity), BOTH_RAN);

        assertThat(round1(score.tech().deductions().getFirst().points())).isEqualTo(expected);
    }

    @Test
    void score_noisyAnalyzer_cannotTakeACategoryBelow60OnItsOwn() {
        HealthScore score =
                ScoringEngine.score(some(500, "deps", Category.TECH, Severity.HIGH), BOTH_RAN);

        assertThat(score.tech().score()).isEqualTo(60);
        assertThat(score.tech().deductions().getFirst().capped()).isTrue();
    }

    @Test
    void score_manyAnalyzersMaxedOut_neverGoesBelowZero() {
        List<Finding> findings = new ArrayList<>();
        for (String analyzer : List.of("a", "b", "c", "d")) {
            findings.addAll(some(100, analyzer, Category.TECH, Severity.HIGH));
        }

        assertThat(ScoringEngine.score(findings, BOTH_RAN).tech().score()).isZero();
    }

    @Test
    void score_oneCritical_capsItsCategoryAndOverallAt50() {
        HealthScore score =
                ScoringEngine.score(
                        some(1, "vuln", Category.SECURITY, Severity.CRITICAL), BOTH_RAN);

        // Without the ceiling: security 85, overall 91
        assertThat(score.security().score()).isEqualTo(50);
        assertThat(score.security().capped()).isTrue();
        assertThat(score.tech().score()).isEqualTo(100);
        assertThat(score.overall()).hasValue(50);
        assertThat(score.overallCapped()).isTrue();
    }

    @Test
    void score_noFindings_is100Everywhere() {
        HealthScore score = ScoringEngine.score(List.of(), BOTH_RAN);

        assertThat(score.security().score()).isEqualTo(100);
        assertThat(score.tech().score()).isEqualTo(100);
        assertThat(score.overall()).hasValue(100);
    }

    @Test
    void score_infoFindings_weighNothing() {
        assertThat(
                        ScoringEngine.score(
                                        some(30, "deps", Category.TECH, Severity.INFO), BOTH_RAN)
                                .overall())
                .hasValue(100);
    }

    @Test
    void score_failedAnalyzer_marksItsCategoryIncomplete() {
        List<AnalyzerResult> results =
                List.of(
                        aResult("deps", Category.TECH, AnalyzerStatus.OK),
                        aResult("vuln", Category.SECURITY, AnalyzerStatus.FAILED),
                        aResult("secrets", Category.SECURITY, AnalyzerStatus.OK));

        HealthScore score = ScoringEngine.score(List.of(), results);

        assertThat(score.security().status()).isEqualTo(Status.INCOMPLETE);
        assertThat(score.tech().status()).isEqualTo(Status.COMPLETE);
    }

    @Test
    void score_categoryThatDidNotRun_hasNoOverallScore() {
        List<AnalyzerResult> results =
                List.of(
                        aResult("secrets", Category.SECURITY, AnalyzerStatus.OK),
                        aResult("deps", Category.TECH, AnalyzerStatus.SKIPPED));

        HealthScore score = ScoringEngine.score(List.of(), results);

        assertThat(score.tech().status()).isEqualTo(Status.NOT_RUN);
        assertThat(score.overall()).isEqualTo(OptionalInt.empty());
    }

    @Test
    void score_sameFindingsInAnyOrder_givesTheSameScore() {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(some(7, "deps", Category.TECH, Severity.MEDIUM));
        findings.addAll(some(3, "deps", Category.TECH, Severity.HIGH));
        findings.addAll(some(5, "vuln", Category.SECURITY, Severity.LOW));
        HealthScore expected = ScoringEngine.score(findings, BOTH_RAN);

        for (long seed = 0; seed < 20; seed++) {
            List<Finding> shuffled = new ArrayList<>(findings);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(ScoringEngine.score(shuffled, BOTH_RAN)).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "100, A", "90, A", "89, B", "75, B", "74, C", "60, C", "59, D", "40, D", "39, F", "0, F"
    })
    void grade_scores_useTheAdrBoundaries(int score, String grade) {
        assertThat(HealthScore.grade(score)).isEqualTo(grade);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    static List<Finding> some(int count, String analyzer, Category category, Severity severity) {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ruleId = analyzer + "/rule";
            findings.add(
                    new Finding(
                            ruleId,
                            category,
                            severity,
                            analyzer + " finding " + severity + " " + i,
                            Location.file("pom.xml", 1000 + i),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Fingerprints.of(ruleId, severity.name(), String.valueOf(i))));
        }
        return findings;
    }

    static AnalyzerResult aResult(String id, Category category, AnalyzerStatus status) {
        return new AnalyzerResult(id, category, status, Duration.ZERO, Optional.empty());
    }
}
