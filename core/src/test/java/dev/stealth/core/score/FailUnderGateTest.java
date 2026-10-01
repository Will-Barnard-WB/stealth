package dev.stealth.core.score;

import static dev.stealth.core.score.ScoringEngineTest.aResult;
import static dev.stealth.core.score.ScoringEngineTest.some;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig.FailUnder;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FailUnderGateTest {

    private static final List<AnalyzerResult> BOTH_RAN =
            List.of(
                    aResult("deps", Category.TECH, AnalyzerStatus.OK),
                    aResult("vuln", Category.SECURITY, AnalyzerStatus.OK));

    // One critical vulnerability: security 50, tech 100, overall 50 (critical ceiling)
    private static final HealthScore SCORE =
            ScoringEngine.score(some(1, "vuln", Category.SECURITY, Severity.CRITICAL), BOTH_RAN);

    @ParameterizedTest
    @CsvSource({"49, 0", "50, 0", "51, 1"})
    void check_overallThreshold_passesAtOrAboveAndFailsBelow(int threshold, int exitCode) {
        FailUnderGate.Result result =
                FailUnderGate.check(SCORE, FailUnder.none().withOverall(threshold));

        assertThat(result.exitCode()).isEqualTo(exitCode);
    }

    @Test
    void check_categoryThresholds_reportEachScoreBelowItsThreshold() {
        FailUnderGate.Result result =
                FailUnderGate.check(
                        SCORE,
                        new FailUnder(
                                OptionalInt.empty(), OptionalInt.of(80), OptionalInt.of(100)));

        assertThat(result.failures()).containsExactly("security score 50 is below 80");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void check_overallThresholdOnAFilteredRun_cannotJudgeAndExits2() {
        List<AnalyzerResult> filtered =
                List.of(
                        aResult("deps", Category.TECH, AnalyzerStatus.OK),
                        aResult("vuln", Category.SECURITY, AnalyzerStatus.NOT_SELECTED));
        HealthScore partial = ScoringEngine.score(List.of(), filtered);

        FailUnderGate.Result result =
                FailUnderGate.check(partial, FailUnder.none().withOverall(10));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error())
                .hasValueSatisfying(e -> assertThat(e).contains("every analyzer"));
    }

    @Test
    void check_techThresholdWhenOnlyTechRan_isJudged() {
        List<AnalyzerResult> techOnly =
                List.of(
                        aResult("deps", Category.TECH, AnalyzerStatus.OK),
                        aResult("vuln", Category.SECURITY, AnalyzerStatus.NOT_SELECTED));
        HealthScore score = ScoringEngine.score(List.of(), techOnly);

        assertThat(
                        FailUnderGate.check(
                                        score,
                                        new FailUnder(
                                                OptionalInt.empty(),
                                                OptionalInt.empty(),
                                                OptionalInt.of(90)))
                                .exitCode())
                .isZero();
    }
}
