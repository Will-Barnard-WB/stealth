package dev.stealth.core.clean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifying edits against a base commit, in a real git repository, with the secrets analyzer. */
class CleanupVerifierTest {

    private static final String PROPERTIES = "src/main/resources/application.properties";

    @TempDir private Path workspace;

    private Path repo;
    private CleanupVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        repo = Fixture.WITH_SECRETS.copyTo(workspace);
        git("init", "--quiet");
        git("add", ".");
        commit("init");
        verifier =
                new CleanupVerifier(
                        new AnalyzerRunner(
                                List.of(new SecretsAnalyzer(AllAnalyzers.REFERENCE_DATE)),
                                Duration.ofSeconds(60)));
    }

    @Test
    void verify_secretRemovedInTheWorkingTree_isResolvedAndNothingIntroduced() throws Exception {
        removeLine(PROPERTIES, "AKIA");

        Verification verification = verify(Optional.empty());

        assertThat(verification.base()).isEqualTo("HEAD");
        assertThat(verification.resolved())
                .extracting(Finding::ruleId)
                .containsExactly("secrets/aws-access-key-id");
        assertThat(verification.introduced()).isEmpty();
        assertThat(verification.findingsAfter()).isEqualTo(verification.findingsBefore() - 1);
        assertThat(verification.green()).isTrue();
    }

    @Test
    void verify_newSecretAdded_isIntroduced() throws Exception {
        Files.writeString(
                repo.resolve("src/main/java/Added.java"),
                "class Added { String token = \"ghp_"
                        + "a".repeat(10)
                        + "Zq8vX3mK9pL2nR7tY4wB6cD1eF0gH5jQ8sU\"; }\n");

        Verification verification = verify(Optional.empty());

        assertThat(verification.introduced())
                .singleElement()
                .satisfies(
                        f -> assertThat(f.location().path()).contains("src/main/java/Added.java"));
        assertThat(verification.green()).isFalse();
    }

    @Test
    void verify_committedOnABranch_comparesWithTheGivenBase() throws Exception {
        String main = git("rev-parse", "HEAD");
        removeLine(PROPERTIES, "AKIA");
        git("add", ".");
        commit("remove the key");

        Verification verification = verify(Optional.of(main));

        assertThat(verification.resolved()).hasSize(1);
    }

    @Test
    void verify_noChangesAndNoBase_saysWhatToCompareWith() {
        assertThatThrownBy(() -> verify(Optional.empty()))
                .isInstanceOf(CleanException.class)
                .hasMessageContaining("nothing to verify");
    }

    private Verification verify(Optional<String> base) throws Exception {
        return verifier.verify(
                new RepoContext(repo, StealthConfig.defaults()), base, Optional.empty());
    }

    private void removeLine(String file, String containing) throws IOException {
        Path path = repo.resolve(file);
        List<String> kept =
                Files.readAllLines(path).stream().filter(l -> !l.contains(containing)).toList();
        Files.write(path, kept);
    }

    private void commit(String message) throws Exception {
        git(
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@example.com",
                "commit",
                "--quiet",
                "-m",
                message);
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output.strip();
    }
}
