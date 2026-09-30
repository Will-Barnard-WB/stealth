package dev.stealth.core.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.0.0-M1",
                "4.2.0-M2",
                "3.0.0.M1",
                "6.0.0-RC2",
                "2.16.0-rc1",
                "5.4.0.CR1",
                "1.0.Beta1",
                "1.0.0-beta.2",
                "4.0.0-alpha-3",
                "2.1.0-alpha0",
                "1.0-SNAPSHOT",
                "1.0.0-preview1",
                "21-ea",
                "1.0-b3"
            })
    void isPreRelease_preReleaseVersions_returnsTrue(String version) {
        assertThat(Versions.isPreRelease(version)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "3.2.2",
                "1.9",
                "33.7.2-jre",
                "33.7.2-android",
                "2.0.0.RELEASE",
                "5.3.0.Final",
                "20040616",
                "1.0.0-GA",
                "2.22.3"
            })
    void isPreRelease_stableVersions_returnsFalse(String version) {
        assertThat(Versions.isPreRelease(version)).isFalse();
    }

    @Test
    void newerVersion_onlyPreReleasesNewer_returnsEmpty() {
        assertThat(
                        Versions.newerVersion(
                                List.of("4.1.0", "4.1.1", "4.2.0-M1", "4.2.0-M2"), "4.1.1"))
                .isEmpty();
    }

    @Test
    void newerVersion_currentIsPreRelease_considersNewerPreReleases() {
        assertThat(Versions.newerVersion(List.of("4.2.0-M1", "4.2.0-M2"), "4.2.0-M1"))
                .contains("4.2.0-M2");
    }

    @Test
    void newerVersion_dateStampedRelics_areNotUpgrades() {
        List<String> commonsCollections =
                List.of("3.2.1", "3.2.2", "20030418.083655", "20031027.000000", "20040616");

        assertThat(Versions.newerVersion(commonsCollections, "3.2.2")).isEmpty();
    }

    @Test
    void newerVersion_variantQualifier_staysOnTheSameVariant() {
        List<String> guava =
                List.of("33.7.1-jre", "33.7.1-android", "33.7.2-android", "33.7.2-jre");

        assertThat(Versions.newerVersion(guava, "30.1-jre")).contains("33.7.2-jre");
        assertThat(Versions.newerVersion(guava, "30.1-android")).contains("33.7.2-android");
    }

    @Test
    void newerVersion_noVariantInCurrent_acceptsVariantVersions() {
        assertThat(Versions.newerVersion(List.of("19.0", "33.7.2-jre"), "19.0"))
                .contains("33.7.2-jre");
    }

    @Test
    void newerVersion_alreadyLatest_returnsEmpty() {
        assertThat(Versions.newerVersion(List.of("2.22.2", "2.22.3"), "2.22.3")).isEmpty();
    }

    @Test
    void newerVersion_qualifierDroppedByNewerReleases_stillFindsThem() {
        assertThat(Versions.newerVersion(List.of("5.2.9.RELEASE", "6.2.1"), "5.2.9.RELEASE"))
                .contains("6.2.1");
    }

    @ParameterizedTest
    @CsvSource({
        "2.7.18,     4.1.1,      MAJOR",
        "30.1-jre,   33.7.2-jre, MAJOR",
        "3.9,        3.21.0,     MINOR",
        "33.6.0-jre, 33.7.2-jre, MINOR",
        "2.0.19,     2.0.20,     PATCH",
        "1.0,        1.0.1,      PATCH",
    })
    void update_versionPairs_classifiesTheChange(
            String current, String latest, Versions.Update expected) {
        assertThat(Versions.update(current, latest)).isEqualTo(expected);
    }
}
