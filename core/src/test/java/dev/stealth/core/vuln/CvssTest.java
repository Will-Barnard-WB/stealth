package dev.stealth.core.vuln;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CvssTest {

    // Scores cross-checked with the FIRST CVSS v3.1 calculator
    @ParameterizedTest
    @CsvSource({
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H, 9.8",
        "CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N, 5.5",
        "CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N, 3.3",
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:L/A:N, 6.5",
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H, 10.0",
        "CVSS:3.1/AV:N/AC:L/PR:L/UI:R/S:C/C:L/I:L/A:N, 5.4",
        "CVSS:3.0/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N, 5.9",
        "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N, 0.0",
    })
    void baseScore_v3Vectors_matchesTheSpecification(String vector, double expected) {
        assertThat(Cvss.baseScore(vector)).hasValue(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
                "AV:N/AC:L/Au:N/C:P/I:P/A:P",
                "CVSS:3.1/AV:N/AC:L",
                "CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"
            })
    void baseScore_unsupportedOrMalformedVector_returnsEmpty(String vector) {
        assertThat(Cvss.baseScore(vector)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "9.8, CRITICAL",
        "9.0, CRITICAL",
        "8.9, HIGH",
        "7.0, HIGH",
        "6.9, MEDIUM",
        "4.0, MEDIUM",
        "3.9, LOW",
        "0.1, LOW",
        "0.0, INFO"
    })
    void severity_scores_useTheAdrBands(double score, Severity expected) {
        assertThat(Cvss.severity(score)).isEqualTo(expected);
    }
}
