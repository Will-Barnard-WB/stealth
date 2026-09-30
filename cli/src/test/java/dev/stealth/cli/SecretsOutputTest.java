package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

/**
 * No renderer may print a secret. Runs the real secrets analyzer on {@code fixtures/with-secrets}
 * and renders the report in its most verbose form. Add the JSON and SARIF renderers here as they
 * arrive.
 */
class SecretsOutputTest {

    /** The fixture's fake secrets, split so they aren't secrets in this file. */
    private static final List<String> FIXTURE_SECRETS =
            List.of(
                    "AKIA" + "YS5J3PX6EAT3544I",
                    "fVc1GFQ3++zbxurxlp4j" + "hvGRDzuqM0Mtcm2sM+nh",
                    "ghp_" + "7ZSXd7lFBilqOuF5j0aiG0IC1DSTRxVsmBCm",
                    "JsQPRIbvPB+73bV6Q0Jr" + "qN9AIVPQIn0SvuRrxxUWDwfHPolTEtTbjdHGbBCiWEh6");

    @Test
    void terminal_withSecretsFixture_showsMaskedPreviewsAndNoSecret() throws Exception {
        Path root = withSecretsFixture();
        DoctorReport report =
                new AnalyzerRunner(
                                List.of(new SecretsAnalyzer(Clock.systemUTC())),
                                Duration.ofSeconds(30))
                        .run(new RepoContext(root, StealthConfig.defaults()));

        String out = new TerminalReport(Ansi.OFF, true).render(root, report, true);

        assertThat(report.findings()).hasSize(4);
        assertThat(out).contains("ghp_********", "AKIA********", "fVc1********", "JsQP********");
        for (String secret : FIXTURE_SECRETS) {
            // Anything past the four visible characters would be a leak
            assertThat(out).doesNotContain(secret.substring(0, 5));
        }
    }

    private static Path withSecretsFixture() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("fixtures").resolve("with-secrets");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No fixtures/with-secrets above the working directory");
    }
}
