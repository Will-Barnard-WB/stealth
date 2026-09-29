package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FingerprintsTest {

    @Test
    void of_noKeys_hashesRuleId() {
        assertThat(Fingerprints.of("hygiene/missing-codeowners"))
                .isEqualTo("v1:4fda421ad6d018b901b3b2039296cb2c134f4c62b89c57e178f15c30b01f238d");
    }

    @Test
    void of_keys_hashesThemNulSeparatedAfterRuleId() {
        assertThat(Fingerprints.of("deps/outdated", "core", "g:a"))
                .isEqualTo("v1:09a551d3b310b8630e9b968c6da5ead05af0817d30e3dc90f0496352209b38ec");
    }

    @Test
    void of_keysSplitDifferently_returnsDifferentFingerprints() {
        assertThat(Fingerprints.of("secrets/generic", "ab", "c"))
                .isNotEqualTo(Fingerprints.of("secrets/generic", "a", "bc"));
    }
}
