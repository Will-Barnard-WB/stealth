package dev.stealth.core.vuln;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FixedVersionsTest {

    private static final String NAME = "org.example:lib";

    @Test
    void fixedVersion_singleRange_returnsItsFix() {
        OsvVulnerability advisory = anAdvisory(aRange("introduced", "1.5", "fixed", "1.10.0"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "1.9")).contains("1.10.0");
    }

    @Test
    void fixedVersion_severalRanges_usesTheRangeTheVersionIsIn() {
        OsvVulnerability advisory =
                anAdvisory(
                        aRange("introduced", "2.0", "fixed", "2.9.10"),
                        aRange("introduced", "2.10.0", "fixed", "2.12.7"),
                        aRange("introduced", "2.13.0", "fixed", "2.13.4"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "2.11.3")).contains("2.12.7");
    }

    @Test
    void fixedVersion_introducedZero_coversEveryEarlierVersion() {
        OsvVulnerability advisory = anAdvisory(aRange("introduced", "0", "fixed", "3.18.0"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "1.0")).contains("3.18.0");
    }

    @Test
    void fixedVersion_lastAffectedOnly_hasNoFix() {
        OsvVulnerability advisory = anAdvisory(aRange("introduced", "0", "last_affected", "4.2"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "4.1")).isEmpty();
    }

    @Test
    void fixedVersion_openRange_hasNoFix() {
        OsvVulnerability advisory = anAdvisory(aRange("introduced", "1.0"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "7.0")).isEmpty();
    }

    @Test
    void fixedVersion_otherVariantListed_reportsTheVariantInUse() {
        OsvVulnerability advisory =
                anAdvisory(aRange("introduced", "1.0", "fixed", "32.0.0-android"));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "30.1-jre")).contains("32.0.0-jre");
    }

    @Test
    void fixedVersion_rangesForRepackagedCopies_areIgnored() {
        OsvVulnerability advisory =
                new OsvVulnerability(
                        "GHSA-test",
                        List.of(),
                        "",
                        List.of(),
                        Optional.empty(),
                        List.of(
                                new OsvVulnerability.Affected(
                                        "Maven",
                                        "com.repackaged:lib",
                                        List.of(aRange("introduced", "0", "fixed", "9.9")),
                                        List.of()),
                                new OsvVulnerability.Affected(
                                        "Maven",
                                        NAME,
                                        List.of(aRange("introduced", "1.0", "fixed", "1.2")),
                                        List.of())));

        assertThat(FixedVersions.fixedVersion(advisory, NAME, "1.1")).contains("1.2");
    }

    private static OsvVulnerability anAdvisory(OsvVulnerability.Range... ranges) {
        return new OsvVulnerability(
                "GHSA-test",
                List.of(),
                "",
                List.of(),
                Optional.empty(),
                List.of(
                        new OsvVulnerability.Affected(
                                "Maven", NAME, Arrays.asList(ranges), List.of())));
    }

    /** Alternating event kinds and versions. */
    private static OsvVulnerability.Range aRange(String... kindsAndVersions) {
        List<OsvVulnerability.Event> events = new ArrayList<>();
        for (int i = 0; i < kindsAndVersions.length; i += 2) {
            events.add(new OsvVulnerability.Event(kindsAndVersions[i], kindsAndVersions[i + 1]));
        }
        return new OsvVulnerability.Range("ECOSYSTEM", events);
    }
}
