package dev.stealth.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Advisory;
import dev.stealth.core.AllAnalyzers;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookChecksTest {

    private static final String TOKEN = "ghp_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5";

    private static final String POM =
            """
            <project>
                <properties>
                    <text.version>1.10.0</text.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-text</artifactId>
                        <version>${text.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.9</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    @TempDir private Path repo;

    private final List<String> checked = new ArrayList<>();
    private HookChecks hooks;
    private Path top;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), POM);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(
                repo.resolve("src/Old.java"), "class Old { String token = \"" + TOKEN + "\"; }\n");
        git("init", "--quiet");
        git("add", ".");
        git(
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@example.com",
                "commit",
                "--quiet",
                "-m",
                "init");
        top = HookChecks.repositoryTop(repo).orElseThrow();
        hooks =
                new HookChecks(
                        new SecretsAnalyzer(AllAnalyzers.REFERENCE_DATE),
                        (groupId, artifactId, version) -> {
                            checked.add(groupId + ":" + artifactId + "@" + version.orElse("?"));
                            return result(groupId, artifactId, version.orElseThrow());
                        },
                        AllAnalyzers.REFERENCE_DATE);
    }

    @Test
    void afterEdit_vulnerableVersionThroughAProperty_saysWhatToUseInstead() throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                POM.replace("<text.version>1.10.0<", "<text.version>1.9<"));

        List<String> problems =
                hooks.afterEdit(top, repo.resolve("pom.xml"), StealthConfig.defaults());

        assertThat(problems)
                .singleElement()
                .asString()
                .startsWith(
                        "pom.xml: org.apache.commons:commons-text 1.9 has 1 known vulnerability"
                                + " (worst critical: CVE-2022-42889).")
                .contains("Use 1.15.0, the latest, which has none.");
        // Only what changed is looked up: commons-lang3 was already there
        assertThat(checked).containsExactly("org.apache.commons:commons-text@1.9");
    }

    @Test
    void afterEdit_inventedArtifact_isFlagged() throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                POM.replace(
                        "    </dependencies>",
                        "       "
                            + " <dependency><groupId>com.example</groupId><artifactId>made-up</artifactId><version>1.0</version></dependency>\n"
                            + "    </dependencies>"));

        assertThat(hooks.afterEdit(top, repo.resolve("pom.xml"), StealthConfig.defaults()))
                .singleElement()
                .asString()
                .contains("com.example:made-up isn't on Maven Central");
    }

    @Test
    void afterEdit_unchangedPom_checksNothing() throws Exception {
        assertThat(hooks.afterEdit(top, repo.resolve("pom.xml"), StealthConfig.defaults()))
                .isEmpty();
        assertThat(checked).isEmpty();
    }

    @Test
    void afterEdit_newHardcodedSecret_isFlaggedButOneAlreadyCommittedIsNot() throws Exception {
        Path added = repo.resolve("src/Client.java");
        Files.writeString(
                added, "class Client { String token = \"" + TOKEN.replace('a', 'q') + "\"; }\n");
        Files.writeString(
                repo.resolve("src/Old.java"),
                Files.readString(repo.resolve("src/Old.java")) + "// edited\n");

        assertThat(hooks.afterEdit(top, added, StealthConfig.defaults()))
                .singleElement()
                .asString()
                .startsWith("src/Client.java:1: GitHub token")
                .contains("environment variable");
        assertThat(hooks.afterEdit(top, repo.resolve("src/Old.java"), StealthConfig.defaults()))
                .isEmpty();
    }

    @Test
    void afterEdit_secretInAnIgnoredPath_isNotFlagged() throws Exception {
        Path added = repo.resolve("src/Client.java");
        Files.writeString(
                added, "class Client { String token = \"" + TOKEN.replace('a', 'q') + "\"; }\n");
        StealthConfig ignoring =
                dev.stealth.core.StealthConfigLoader.parse(
                                "version: 1\nignore: [\"src/**\"]\n",
                                ".stealth.yml",
                                java.util.Set.of(),
                                java.util.Set.of())
                        .config();

        assertThat(hooks.afterEdit(top, added, ignoring)).isEmpty();
    }

    @Test
    void beforeStop_collectsWhatEveryChangedFileIntroduced() throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                POM.replace("<text.version>1.10.0<", "<text.version>1.9<"));
        Files.writeString(
                repo.resolve("src/Client.java"),
                "class Client { String token = \"" + TOKEN.replace('a', 'q') + "\"; }\n");

        assertThat(hooks.beforeStop(top, StealthConfig.defaults()))
                .hasSize(2)
                .anySatisfy(p -> assertThat(p).startsWith("pom.xml: "))
                .anySatisfy(p -> assertThat(p).startsWith("src/Client.java:1: "));
    }

    @Test
    void beforeStop_nothingChanged_isQuiet() throws Exception {
        assertThat(hooks.beforeStop(top, StealthConfig.defaults())).isEmpty();
    }

    /**
     * Fake Central + OSV: commons-text 1.9 has Text4Shell; anything under com.example doesn't
     * exist.
     */
    private static DependencyCheck.Result result(
            String groupId, String artifactId, String version) {
        if (groupId.equals("com.example")) {
            return new DependencyCheck.Result(
                    groupId,
                    artifactId,
                    false,
                    Optional.of(version),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Optional.empty());
        }
        List<Finding> vulnerabilities =
                version.equals("1.9")
                        ? List.of(
                                new Finding(
                                        "vuln/known-vulnerability",
                                        Category.SECURITY,
                                        Severity.CRITICAL,
                                        "Text4Shell",
                                        Location.repository(),
                                        List.of(),
                                        Optional.empty(),
                                        Optional.of(
                                                new Advisory(
                                                        "GHSA-599f-7c49-w659",
                                                        List.of("CVE-2022-42889"),
                                                        OptionalDouble.of(9.8),
                                                        Optional.empty())),
                                        Optional.of(
                                                new Remediation(
                                                        Optional.of("1.10.0"), Optional.empty())),
                                        Fingerprints.of("vuln/known-vulnerability", "x")))
                        : List.of();
        return new DependencyCheck.Result(
                groupId,
                artifactId,
                true,
                Optional.of(version),
                Optional.of("1.15.0"),
                Optional.empty(),
                Optional.of(true),
                vulnerabilities,
                Optional.of(0));
    }

    private void git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git failed: " + output);
        }
    }
}
