package dev.stealth.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretValuesTest {

    @Test
    void entropy_randomBase64_isAboveTheGenericThresholdAndWordsAreBelow() {
        assertThat(SecretValues.entropy("fVc1GFQ3++zbxurxlp4j" + "hvGRDzuqM0Mtcm2sM+nh"))
                .isGreaterThan(4.5);
        assertThat(SecretValues.entropy("changeme")).isLessThan(3.5);
        assertThat(SecretValues.entropy("aaaa")).isZero();
        assertThat(SecretValues.entropy("ab")).isCloseTo(1.0, within(1e-9));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "AKIAIOSFODNN7EXAMPLE",
                "${DB_PASSWORD}",
                "#{systemProperties.pw}",
                "{{ secrets.TOKEN }}",
                "<your-api-key>",
                "changeme",
                "your_token_here",
                "xxxxxxxxxxxxxxxx",
                "0000000000000000"
            })
    void isPlaceholder_documentationValues_areRecognised(String value) {
        assertThat(SecretValues.isPlaceholder(value)).isTrue();
    }

    @Test
    void isPlaceholder_randomValue_isNot() {
        assertThat(SecretValues.isPlaceholder("fVc1GFQ3++zbxurxlp4j" + "hvGRDzuqM0Mtcm2sM+nh"))
                .isFalse();
    }

    @Test
    void looksLikeName_hexUnderAHashKey_isANameButUnderATokenKeyIsNot() {
        String hex = "9fceb02d0ae598e95dc970b74767f19372d61af8";

        assertThat(SecretValues.looksLikeName(hex, "build.commit-sha")).isTrue();
        assertThat(SecretValues.looksLikeName(hex, "github.token")).isFalse();
    }

    @Test
    void looksLikeName_base64WithSlashes_isNotMistakenForAPath() {
        assertThat(SecretValues.looksLikeName("wJalrXUtnFEMI/K7MDENG/bPxRfiCY", "secret"))
                .isFalse();
    }

    @Test
    void mask_showsOnlyTheFirstFourCharactersAndHidesTheLength() {
        assertThat(SecretValues.mask("ghp_" + "7ZSXd7lFBilqOuF5j0aiG0IC1DSTRxVsmBCm"))
                .isEqualTo("ghp_********");
        assertThat(SecretValues.mask("AKIA" + "YS5J3PX6EAT3544I")).isEqualTo("AKIA********");
        assertThat(SecretValues.mask("abcdefgh")).isEqualTo("ab********");
        assertThat(SecretValues.mask("\"\n\tabcdefghijkl")).isEqualTo("\"??a********");
    }
}
