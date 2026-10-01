package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.secrets.SecretsAnalyzer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@code .stealth.yml} applied by the runner to a real analyzer on a fixture. */
class StealthConfigIntegrationTest {

    private static final Clock REFERENCE_DATE =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);

    private final AnalyzerRunner runner =
            new AnalyzerRunner(
                    List.of(new SecretsAnalyzer(REFERENCE_DATE)), Duration.ofSeconds(60));

    @Test
    void ignore_withSecrets_removesFindingsUnderIgnoredPathsBeforeAnalysis() throws Exception {
        StealthConfig config = load("version: 1\nignore:\n  - \"src/main/resources/**\"\n");

        DoctorReport report = run(config);

        assertThat(report.findings())
                .extracting(f -> f.location().path().orElseThrow())
                .containsExactly("src/main/java/com/example/secrets/GitHubClient.java");
    }

    @Test
    void severity_withSecrets_overrideChangesTheScore() throws Exception {
        DoctorReport defaults = run(StealthConfig.defaults());
        DoctorReport downgraded =
                run(
                        load(
                                """
                                version: 1
                                severity:
                                  secrets/aws-access-key-id: low
                                  secrets/github-token: low
                                  secrets/private-key: low
                                """));

        assertThat(downgraded.findings()).hasSameSizeAs(defaults.findings());
        assertThat(downgraded.score().security().score())
                .isGreaterThan(defaults.score().security().score());
    }

    @Test
    void severityOff_withSecrets_dropsTheRule() throws Exception {
        DoctorReport report = run(load("version: 1\nseverity:\n  secrets/private-key: off\n"));

        assertThat(report.findings()).noneMatch(f -> f.ruleId().equals("secrets/private-key"));
    }

    private DoctorReport run(StealthConfig config) throws InterruptedException {
        return runner.run(new RepoContext(Fixture.WITH_SECRETS.path(), config));
    }

    private static StealthConfig load(String yaml) throws ConfigException {
        return StealthConfigLoader.parse(yaml, ".stealth.yml", Set.of(), Set.of()).config();
    }
}
