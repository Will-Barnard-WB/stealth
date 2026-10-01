package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StealthConfigLoaderTest {

    private static final Set<String> RULES =
            Set.of(
                    "deps/outdated-major",
                    "duplication/cpd",
                    "hygiene/missing-codeowners",
                    "secrets/generic-secret",
                    "maintenance/no-recent-release");
    private static final Set<String> ANALYZERS =
            Set.of("deps", "vuln", "maintenance", "eol", "secrets", "duplication", "hygiene");

    // The ADR-0004 example, with this version's rule ids, plus the analyzers section
    static final String FULL_EXAMPLE =
            """
            version: 1
            ignore:
              - "fixtures/**"
              - "**/generated/**"
            severity:
              deps/outdated-major: low
              duplication/cpd: info
              hygiene/missing-codeowners: off
            allow:
              secrets:
                - path: "src/test/resources/fake-credentials.properties"
                  reason: Test fixtures, not real keys
                - fingerprint: "v1:c77e0939cbd45a129c6cbb29a6edaa001f40e8b2954e3714c1c2532257ee7bd7"
                  reason: Key revoked on 2026-08-14
              dependencies:
                - component: "pkg:maven/commons-collections/commons-collections"
                  rules: [maintenance/no-recent-release]
                  reason: Only used by the legacy importer, which is being removed
                  expires: 2026-12-31
                - advisory: GHSA-jjjh-jjxp-wpff
                  reason: Not reachable
                  expires: 2026-11-30
            analyzers:
              duplication: { min-tokens: 150, include-tests: true }
              maintenance: { stale-after: P3Y }
              hygiene: { stale-branch-after: P180D, large-file-bytes: 10485760 }
              eol: { enabled: false }
            eol:
              java-distribution: oracle-jdk
              java-version: "17"
            fail-under:
              overall: 70
              security: 80
            """;

    @Test
    void parse_fullExample_readsEverySection() throws Exception {
        StealthConfigLoader.Loaded loaded = parse(FULL_EXAMPLE);
        StealthConfig config = loaded.config();

        assertThat(loaded.warnings()).isEmpty();
        assertThat(config.isIgnored("fixtures/boot2-legacy/pom.xml")).isTrue();
        assertThat(config.isIgnored("core/target/generated/Foo.java")).isTrue();
        assertThat(config.isIgnored("src/main/java/App.java")).isFalse();
        assertThat(config.severity())
                .containsEntry("deps/outdated-major", Optional.of(Severity.LOW))
                .containsEntry("duplication/cpd", Optional.of(Severity.INFO))
                .containsEntry("hygiene/missing-codeowners", Optional.empty());
        assertThat(config.secrets().allow()).hasSize(2);
        assertThat(config.allowedDependencies())
                .extracting(StealthConfig.AllowedDependency::expires)
                .containsExactly(
                        Optional.of(LocalDate.parse("2026-12-31")),
                        Optional.of(LocalDate.parse("2026-11-30")));
        assertThat(config.thresholds().duplicationMinTokens()).hasValue(150);
        assertThat(config.thresholds().duplicationIncludeTests()).contains(true);
        assertThat(config.thresholds().maintenanceStaleAfter()).contains(Period.ofYears(3));
        assertThat(config.thresholds().hygieneStaleBranchAfter()).contains(Period.ofDays(180));
        assertThat(config.thresholds().hygieneLargeFileBytes()).hasValue(10_485_760L);
        assertThat(config.isEnabled("eol")).isFalse();
        assertThat(config.eol().javaDistribution()).isEqualTo("oracle-jdk");
        assertThat(config.eol().javaVersion()).contains("17");
        assertThat(config.failUnder().overall()).hasValue(70);
        assertThat(config.failUnder().security()).hasValue(80);
        assertThat(config.failUnder().tech()).isEmpty();
    }

    @Test
    void parse_emptyFile_givesTheDefaults() throws Exception {
        StealthConfigLoader.Loaded loaded = parse("");

        assertThat(loaded.config()).isEqualTo(StealthConfig.defaults());
        assertThat(loaded.warnings()).isEmpty();
    }

    @Test
    void load_noFile_givesTheDefaults(@TempDir Path repo) throws Exception {
        StealthConfigLoader.Loaded loaded = StealthConfigLoader.load(repo, RULES, ANALYZERS);

        assertThat(loaded.config()).isEqualTo(StealthConfig.defaults());
        assertThat(loaded.file()).isEmpty();
    }

    @Test
    void load_bothYmlAndYaml_fails(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve(".stealth.yml"), "version: 1\n");
        Files.writeString(repo.resolve(".stealth.yaml"), "version: 1\n");

        assertThatThrownBy(() -> StealthConfigLoader.load(repo, RULES, ANALYZERS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("both .stealth.yml and .stealth.yaml");
    }

    @Test
    void parse_unknownKeys_warnWithLineAndSuggestionAndCarryOn() throws Exception {
        StealthConfigLoader.Loaded loaded =
                parse(
                        """
                        version: 1
                        ignroe:
                          - "x/**"
                        fail-under: 60
                        severity:
                          duplication/cdp: low
                        analyzers:
                          hygeine: { enabled: false }
                        """);

        assertThat(loaded.warnings())
                .containsExactly(
                        ".stealth.yml:2: unknown key 'ignroe' (did you mean 'ignore'?)",
                        ".stealth.yml:6: unknown rule 'duplication/cdp' (did you mean"
                                + " 'duplication/cpd'?)",
                        ".stealth.yml:8: unknown analyzer 'hygeine' (did you mean 'hygiene'?)");
        assertThat(loaded.config().failUnder().overall()).hasValue(60);
    }

    @Test
    void parse_missingVersion_warnsAndReadsVersion1() throws Exception {
        assertThat(parse("fail-under: 60\n").warnings())
                .containsExactly(".stealth.yml:1: no 'version'; reading it as version 1");
    }

    @Test
    void parse_allowEntryWithoutReason_warns() throws Exception {
        StealthConfigLoader.Loaded loaded =
                parse(
                        """
                        version: 1
                        allow:
                          secrets:
                            - path: "src/test/**"
                        """);

        assertThat(loaded.warnings()).singleElement().asString().contains(":4:", "no 'reason'");
    }

    @Test
    void parse_camelCaseKeys_areAccepted() throws Exception {
        StealthConfig config =
                parse(
                                """
                                version: 1
                                eol:
                                  javaDistribution: amazon-corretto
                                analyzers:
                                  maintenance: { staleAfter: P1Y }
                                """)
                        .config();

        assertThat(config.eol().javaDistribution()).isEqualTo("amazon-corretto");
        assertThat(config.thresholds().maintenanceStaleAfter()).contains(Period.ofYears(1));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "fail-under: 120                               | .stealth.yml:2: fail-under must be"
                        + " between 0 and 100, got 120",
                "fail-under: { tech: -1 }                      | .stealth.yml:2: fail-under.tech"
                        + " must be between 0 and 100, got -1",
                "fail-under: lots                              | .stealth.yml:2: fail-under must be"
                        + " a whole number, got 'lots'",
                "severity: { deps/outdated-major: urgent }     | .stealth.yml:2: unknown severity"
                        + " 'urgent' for deps/outdated-major",
                "ignore: src/**                                | .stealth.yml:2: ignore must be a"
                        + " list",
                "analyzers: { maintenance: { stale-after: 2y } } | .stealth.yml:2:"
                        + " analyzers.maintenance.stale-after must be an ISO-8601 period",
                "analyzers: { duplication: { min-tokens: 0 } } | .stealth.yml:2: min-tokens must be"
                        + " at least 1",
                "analyzers: { eol: { enabled: maybe } }        | .stealth.yml:2:"
                        + " analyzers.eol.enabled must be true or false",
                "allow: { dependencies: [ { reason: x } ] }    | .stealth.yml:2: an"
                        + " allow.dependencies entry needs a 'component' or an 'advisory'",
                "allow: { dependencies: [ { component: commons-collections, reason: x } ] } |"
                        + " .stealth.yml:2: component must be a Package URL",
                "allow: { secrets: [ { path: a, reason: x, expires: soon } ] } | .stealth.yml:2:"
                        + " expires must be a date such as 2026-12-31, got 'soon'",
            })
    void parse_invalidValue_failsWithTheLine(String yaml, String message) {
        assertThatThrownBy(() -> parse("version: 1\n" + yaml.strip() + "\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageStartingWith(message);
    }

    @Test
    void parse_newerVersion_failsAskingForANewerStealth() {
        assertThatThrownBy(() -> parse("version: 2\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessage(
                        ".stealth.yml:1: version 2 needs a newer stealth; this one reads version"
                                + " 1");
    }

    @Test
    void parse_brokenYaml_failsWithTheLine() {
        assertThatThrownBy(() -> parse("version: 1\nignore: [\"a\"\nfail-under: 70\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageStartingWith(".stealth.yml:")
                .hasMessageContaining("invalid YAML");
    }

    @Test
    void parse_duplicateKey_fails() {
        assertThatThrownBy(() -> parse("version: 1\nfail-under: 60\nfail-under: 70\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appears twice");
    }

    private static StealthConfigLoader.Loaded parse(String yaml) throws ConfigException {
        return StealthConfigLoader.parse(yaml, ".stealth.yml", RULES, ANALYZERS);
    }
}
