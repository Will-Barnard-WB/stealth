package dev.stealth.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Fixture;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.StealthConfig.AllowedSecret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Precision tests against the fixtures (fixtures/README.md), plus allowlists and file filters. */
class SecretsAnalyzerTest {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 9, 29);

    private static final String PROPERTIES = "src/main/resources/application.properties";
    private static final String GITHUB_CLIENT =
            "src/main/java/com/example/secrets/GitHubClient.java";
    private static final String DEPLOY_KEY = "src/main/resources/deploy-key.pem";

    /** The with-secrets fixture's fake values, split so they aren't secrets in this file. */
    private static final List<String> FIXTURE_SECRETS =
            List.of(
                    "AKIA" + "YS5J3PX6EAT3544I",
                    "fVc1GFQ3++zbxurxlp4j" + "hvGRDzuqM0Mtcm2sM+nh",
                    "ghp_" + "7ZSXd7lFBilqOuF5j0aiG0IC1DSTRxVsmBCm",
                    "JsQPRIbvPB+73bV6Q0Jr" + "qN9AIVPQIn0SvuRrxxUWDwfHPolTEtTbjdHGbBCiWEh6");

    private static final String TOKEN = "ghp_" + "DzOUoRtOGT8UPA0bdKTWrD63WLBb0cF3flNI";

    @TempDir private Path tempDir;

    private final SecretsAnalyzer analyzer = analyzer(REFERENCE_DATE);

    @Test
    void analyze_withSecrets_flagsExactlyTheRealPatterns() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.WITH_SECRETS.context());

        assertThat(findings)
                .extracting(
                        Finding::ruleId,
                        f -> f.location().path().orElseThrow(),
                        f -> f.location().line().getAsInt(),
                        Finding::severity)
                .containsExactly(
                        tuple("secrets/github-token", GITHUB_CLIENT, 6, Severity.CRITICAL),
                        tuple("secrets/aws-access-key-id", PROPERTIES, 5, Severity.CRITICAL),
                        tuple("secrets/generic-secret", PROPERTIES, 6, Severity.HIGH),
                        tuple("secrets/private-key", DEPLOY_KEY, 1, Severity.CRITICAL));
        assertThat(findings).extracting(Finding::category).containsOnly(Category.SECURITY);
    }

    @Test
    void analyze_withSecrets_masksEverySecretInTheMessage() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.WITH_SECRETS.context());

        assertThat(findings)
                .extracting(Finding::message)
                .containsExactly(
                        "GitHub token (ghp_********) is hardcoded.",
                        "AWS access key id (AKIA********) is hardcoded.",
                        "High-entropy secret (fVc1********) is hardcoded.",
                        "Private key (JsQP********) is hardcoded.");
        for (Finding finding : findings) {
            assertThat(finding.toString())
                    .doesNotContain(
                            FIXTURE_SECRETS.stream()
                                    .map(s -> s.substring(0, 12))
                                    .toArray(String[]::new));
        }
    }

    @Test
    void analyze_withSecrets_fingerprintsThePathAndTheValuesHashNotTheValue() throws Exception {
        Finding aws = analyzer.analyze(Fixture.WITH_SECRETS.context()).get(1);

        assertThat(aws.fingerprint())
                .isEqualTo(
                        Fingerprints.of(
                                "secrets/aws-access-key-id",
                                PROPERTIES,
                                sha256(FIXTURE_SECRETS.getFirst())));
    }

    @Test
    void analyze_withSecretsCopiedOutsideGit_findsTheSameSecrets() throws Exception {
        Path copy = Fixture.WITH_SECRETS.copyTo(tempDir);

        List<Finding> inRepo = analyzer.analyze(Fixture.WITH_SECRETS.context());
        List<Finding> copied = analyzer.analyze(context(copy));

        assertThat(copied).isEqualTo(inRepo);
    }

    @Test
    void analyze_windowsLineEndings_findsTheSameSecretsWithTheSameFingerprints() throws Exception {
        Path copy = Fixture.WITH_SECRETS.copyTo(tempDir);
        try (var files = java.nio.file.Files.walk(copy)) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                String text = java.nio.file.Files.readString(file);
                java.nio.file.Files.writeString(
                        file, text.replace("\r\n", "\n").replace("\n", "\r\n"));
            }
        }

        List<Finding> crlf = analyzer.analyze(context(copy));

        assertThat(crlf)
                .extracting(Finding::ruleId, Finding::fingerprint)
                .containsExactlyElementsOf(
                        analyzer.analyze(Fixture.WITH_SECRETS.context()).stream()
                                .map(
                                        f ->
                                                org.assertj.core.groups.Tuple.tuple(
                                                        f.ruleId(), f.fingerprint()))
                                .toList());
    }

    /** Every fixture except with-secrets must say "no secrets". */
    @ParameterizedTest
    @EnumSource(value = Fixture.class, names = "WITH_SECRETS", mode = EnumSource.Mode.EXCLUDE)
    void analyze_otherFixtures_findsNothing(Fixture fixture) throws Exception {
        assertThat(analyzer.analyze(fixture.context())).isEmpty();
    }

    @Test
    void analyze_allowedByFingerprint_dropsOnlyThatSecret() throws Exception {
        String fingerprint = analyzer.analyze(Fixture.WITH_SECRETS.context()).get(1).fingerprint();

        List<Finding> findings =
                analyzer.analyze(
                        context(
                                Fixture.WITH_SECRETS.path(),
                                AllowedSecret.fingerprint(fingerprint, "revoked 2026-08-14")));

        assertThat(findings)
                .extracting(Finding::ruleId)
                .containsExactly(
                        "secrets/github-token", "secrets/generic-secret", "secrets/private-key");
    }

    @Test
    void analyze_allowedByPathGlob_dropsEverySecretInMatchingFiles() throws Exception {
        List<Finding> findings =
                analyzer.analyze(
                        context(
                                Fixture.WITH_SECRETS.path(),
                                AllowedSecret.path("src/main/resources/**", "test keys")));

        assertThat(findings).extracting(Finding::ruleId).containsExactly("secrets/github-token");
    }

    @Test
    void analyze_allowedByNameGlob_matchesAtAnyDepth() throws Exception {
        List<Finding> findings =
                analyzer.analyze(
                        context(Fixture.WITH_SECRETS.path(), AllowedSecret.path("*.pem", "keys")));

        assertThat(findings).extracting(Finding::ruleId).doesNotContain("secrets/private-key");
        assertThat(findings).hasSize(3);
    }

    @Test
    void analyze_allowedPathLimitedToRules_keepsOtherRulesInThatFile() throws Exception {
        AllowedSecret genericOnly =
                new AllowedSecret(
                        Optional.of(dev.stealth.core.PathGlob.of("**/application.properties")),
                        Optional.empty(),
                        Set.of("secrets/generic-secret"),
                        "not a real secret",
                        Optional.empty());

        List<Finding> findings =
                analyzer.analyze(context(Fixture.WITH_SECRETS.path(), genericOnly));

        assertThat(findings)
                .extracting(Finding::ruleId)
                .contains("secrets/aws-access-key-id")
                .doesNotContain("secrets/generic-secret");
    }

    @Test
    void analyze_expiredAllowEntry_stopsApplyingTheDayAfter() throws Exception {
        AllowedSecret untilReferenceDate =
                new AllowedSecret(
                        Optional.of(dev.stealth.core.PathGlob.of("**")),
                        Optional.empty(),
                        Set.of(),
                        "temporary",
                        Optional.of(REFERENCE_DATE));
        RepoContext context = context(Fixture.WITH_SECRETS.path(), untilReferenceDate);

        assertThat(analyzer(REFERENCE_DATE).analyze(context)).isEmpty();
        assertThat(analyzer(REFERENCE_DATE.plusDays(1)).analyze(context)).hasSize(4);
    }

    @Test
    void analyze_inlineAllowOnTheSameLine_dropsTheSecret() throws Exception {
        Path copy = Fixture.WITH_SECRETS.copyTo(tempDir);
        Path client = copy.resolve(GITHUB_CLIENT);
        String source = Files.readString(client);
        Files.writeString(
                client,
                source.replace(
                        "private static final String TOKEN = \"" + FIXTURE_SECRETS.get(2) + "\";",
                        "private static final String TOKEN = \""
                                + FIXTURE_SECRETS.get(2)
                                + "\"; // stealth:allow test token"));

        assertThat(analyzer.analyze(context(copy)))
                .extracting(Finding::ruleId)
                .doesNotContain("secrets/github-token")
                .hasSize(3);
    }

    @Test
    void analyze_inlineAllowOnTheCommentLineAbove_dropsOnlyTheNextLine() throws Exception {
        Path copy = Fixture.WITH_SECRETS.copyTo(tempDir);
        Path properties = copy.resolve(PROPERTIES);
        String text = Files.readString(properties);
        Files.writeString(
                properties,
                text.replace("aws.access-key-id=", "# stealth:allow sandbox\naws.access-key-id="));

        assertThat(analyzer.analyze(context(copy)))
                .extracting(Finding::ruleId)
                .doesNotContain("secrets/aws-access-key-id")
                .contains("secrets/generic-secret");
    }

    @Test
    void analyze_inlineAllowInAValueAbove_doesNotAllowTheNextLine() throws Exception {
        write("a.yml", "note: \"see stealth:allow docs\"\ngithub: " + TOKEN + "\n");

        assertThat(analyzer.analyze(context(tempDir))).hasSize(1);
    }

    @Test
    void analyze_buildOutputLockfilesBinariesAndLargeFiles_areSkipped() throws Exception {
        write("src/Found.java", "String t = \"" + TOKEN + "\";");
        write("target/classes/Copied.java", "String t = \"" + TOKEN + "\";");
        write("module/target/app.properties", "t=" + TOKEN);
        write("package-lock.json", "{\"t\": \"" + TOKEN + "\"}");
        Files.write(tempDir.resolve("blob.bin"), ("\0" + TOKEN).getBytes(StandardCharsets.UTF_8));
        write("big.txt", TOKEN + "\n" + "x".repeat((int) SecretsAnalyzer.MAX_FILE_SIZE));

        assertThat(analyzer.analyze(context(tempDir)))
                .extracting(f -> f.location().path().orElseThrow())
                .containsExactly("src/Found.java");
    }

    @Test
    void analyze_specificRuleAndGenericRuleOnTheSameValue_reportsTheSpecificOneOnce()
            throws Exception {
        write("App.java", "static final String GITHUB_TOKEN = \"" + TOKEN + "\";");

        assertThat(analyzer.analyze(context(tempDir)))
                .extracting(Finding::ruleId)
                .containsExactly("secrets/github-token");
    }

    @Test
    void analyze_sameSecretInTwoFiles_givesTwoFindingsWithDifferentFingerprints() throws Exception {
        write("a.properties", "t=" + TOKEN);
        write("b.properties", "t=" + TOKEN);

        List<Finding> findings = analyzer.analyze(context(tempDir));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).fingerprint()).isNotEqualTo(findings.get(1).fingerprint());
    }

    @Test
    void rules_matchesTheBundledRuleset() {
        assertThat(analyzer.rules())
                .extracting(r -> r.id())
                .startsWith("secrets/aws-access-key-id")
                .endsWith("secrets/generic-secret");
    }

    private void write(String path, String content) throws IOException {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static SecretsAnalyzer analyzer(LocalDate today) {
        return new SecretsAnalyzer(
                Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private static RepoContext context(Path root, AllowedSecret... allowed) {
        return new RepoContext(
                root,
                new StealthConfig(
                        Set.of(),
                        StealthConfig.Eol.defaults(),
                        new StealthConfig.Secrets(List.of(allowed))));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
