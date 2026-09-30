package dev.stealth.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.Category;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Matches and near-misses for every bundled rule. Values are fake, from a fixed seed. Token
 * literals are split ({@code "ghp_" + "..."}) so that neither GitHub push protection nor stealth's
 * own scan of this repo mistakes them for real ones.
 */
class SecretRulesTest {

    private static final List<SecretRule> RULES = SecretRules.bundled();

    private static final String AWS_KEY_ID = "AKIA" + "QW3E5R7TY2UIOP4A";
    // stealth:allow fake, like every value here
    private static final String AWS_SECRET = "cZBOIYmeTH9u4In/f2eI" + "TkPBsgqgiaJgm32Brws+";
    private static final String GITHUB_TOKEN = "ghp_" + "DzOUoRtOGT8UPA0bdKTWrD63WLBb0cF3flNI";
    private static final String GITHUB_PAT =
            "github_pat_"
                + "we7yRuY1eY47z5cp7aGrC3_GYIgmVXNIVk5rWwXJAHrjVl7c3zcFQPKIqqSaHu4UmvPqv8leix2NqB1D0r";
    private static final String SLACK_TOKEN =
            "xoxb-" + "123456789012-1234567890123-uEOc84J1tqSU4Xfcsk3rWzAS";
    // stealth:allow fake
    private static final String SLACK_WEBHOOK_SECRET =
            "TCCREVXAC/BN6CFYYY0/" + "i2svQVT0L7TdZLOxT1xwWbPh";
    private static final String STRIPE_KEY = "sk_live_" + "qe4ZEMIkxINHmzWZ23h9e3h6";
    private static final String GOOGLE_KEY = "AIza" + "Hm0_wUhYk_euQglSiDbBeQv31QE03Exo5d6";
    private static final String KEY_ID = "d5432f0fbb5ce62ddd1bc5741f1de5b344bc8473";
    private static final String JWT =
            "eyJ"
                    + "hbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0."
                    + "CQ1OFRaW2mNx0zGq-8BmOon02cSO7ToAvAsjo0mDZ1y";
    private static final String KEY_BODY =
            "hwpnTNZMZCG+AcPv0DEB/98Tn4ybhqzHwmULlzPKqNav19Mi/nw4zoiGimN51jkv\n"
                    + "f6gr8bgmFr/Qje/gd6r8GFjFpOPYOXJnil/duGmwiWl042CGrEOj2/nDi1miROLt\n"
                    + "seVJHW5yy3svYaKoO11+IX44C06jCUmJBvw10quMOz22UqWHF0jhrDgmTINt57Kk\n";
    private static final String GENERIC = "5DZM80ioEpai2E+IWHh" + "euEIPJiUfW/2q";
    private static final String PASSWORD = "zsNQ671fXmfU" + "nu66FT9@";

    /** A rule id, a file extension, the file's text, and the secret it must report. */
    record Case(String ruleId, String extension, String content, String secret) {
        @Override
        public String toString() {
            return ruleId + " in ." + extension + ": " + content.lines().findFirst().orElse("");
        }
    }

    static List<Case> matches() {
        return List.of(
                new Case(
                        "secrets/aws-access-key-id", "properties", "key=" + AWS_KEY_ID, AWS_KEY_ID),
                new Case(
                        "secrets/aws-access-key-id",
                        "java",
                        "String id = \"" + "ASIA" + "QW3E5R7TY2UIOP4A\";",
                        "ASIA" + "QW3E5R7TY2UIOP4A"),
                new Case(
                        "secrets/aws-secret-access-key",
                        "properties",
                        "aws.secret-access-key=" + AWS_SECRET,
                        AWS_SECRET),
                new Case(
                        "secrets/aws-secret-access-key",
                        "ini",
                        "aws_secret_access_key = " + AWS_SECRET,
                        AWS_SECRET),
                new Case(
                        "secrets/github-token",
                        "java",
                        "t = \"" + GITHUB_TOKEN + "\";",
                        GITHUB_TOKEN),
                new Case(
                        "secrets/github-token",
                        "yml",
                        "token: " + "ghs_" + "DzOUoRtOGT8UPA0bdKTWrD63WLBb0cF3flNI",
                        "ghs_" + "DzOUoRtOGT8UPA0bdKTWrD63WLBb0cF3flNI"),
                new Case(
                        "secrets/github-fine-grained-token",
                        "sh",
                        "export T=" + GITHUB_PAT,
                        GITHUB_PAT),
                new Case("secrets/slack-token", "properties", "slack=" + SLACK_TOKEN, SLACK_TOKEN),
                new Case(
                        "secrets/slack-webhook-url",
                        "yml",
                        "url: https://hooks.slack.com/services/" + SLACK_WEBHOOK_SECRET,
                        SLACK_WEBHOOK_SECRET),
                new Case(
                        "secrets/stripe-secret-key",
                        "js",
                        "stripe('" + STRIPE_KEY + "')",
                        STRIPE_KEY),
                new Case(
                        "secrets/google-api-key",
                        "json",
                        "{\"key\": \"" + GOOGLE_KEY + "\"}",
                        GOOGLE_KEY),
                new Case(
                        "secrets/gcp-service-account-key",
                        "json",
                        "{\"type\": \"service_account\", \"private_key_id\": \"" + KEY_ID + "\"}",
                        KEY_ID),
                new Case(
                        "secrets/private-key",
                        "pem",
                        "-----BEGIN RSA PRIVATE KEY-----\n"
                                + KEY_BODY
                                + "-----END RSA PRIVATE KEY-----\n",
                        KEY_BODY),
                new Case(
                        "secrets/private-key",
                        "key",
                        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                                + KEY_BODY
                                + "-----END OPENSSH PRIVATE KEY-----",
                        KEY_BODY),
                new Case(
                        "secrets/private-key",
                        "json",
                        "{\"private_key\": \"-----BEGIN PRIVATE KEY-----\\n"
                                + KEY_BODY.replace("\n", "\\n")
                                + "-----END PRIVATE KEY-----\\n\"}",
                        KEY_BODY.replace("\n", "\\n")),
                new Case("secrets/jwt", "http", "Authorization: Bearer " + JWT, JWT),
                new Case("secrets/generic-secret", "properties", "app.api-key=" + GENERIC, GENERIC),
                new Case(
                        "secrets/generic-secret",
                        "yml",
                        "spring:\n  datasource:\n    password: \"" + PASSWORD + "\"",
                        PASSWORD),
                new Case("secrets/generic-secret", "env", "DB_PASSWORD=" + PASSWORD, PASSWORD),
                new Case(
                        "secrets/generic-secret",
                        "java",
                        "private static final String CLIENT_SECRET = \"" + GENERIC + "\";",
                        GENERIC),
                new Case(
                        "secrets/generic-secret",
                        "kt",
                        "val apiKey = \"" + PASSWORD + "\"",
                        PASSWORD),
                new Case(
                        "secrets/generic-secret",
                        "xml",
                        "<server><password>" + PASSWORD + "</password></server>",
                        PASSWORD));
    }

    /** Look-alikes each rule must not report. */
    static List<Case> nearMisses() {
        return List.of(
                // AWS: documented example, wrong length, non-base32 digits, inside a longer word
                new Case("secrets/aws-access-key-id", "properties", "k=AKIAIOSFODNN7EXAMPLE", ""),
                new Case(
                        "secrets/aws-access-key-id",
                        "properties",
                        "k=AKIA" + "QW3E5R7TY2UIOP4",
                        ""),
                new Case(
                        "secrets/aws-access-key-id",
                        "properties",
                        "k=AKIA" + "QW3E5R7TY2UIOP48",
                        ""),
                new Case("secrets/aws-access-key-id", "txt", "XAKIA" + "QW3E5R7TY2UIOP4A", ""),
                new Case(
                        "secrets/aws-secret-access-key",
                        "properties",
                        "aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        ""),
                new Case(
                        "secrets/aws-secret-access-key",
                        "properties",
                        "aws_secret_access_key=${AWS_SECRET_ACCESS_KEY}",
                        ""),
                // GitHub: too short, and a placeholder
                new Case("secrets/github-token", "java", "\"ghp_" + "DzOUoRtOGT8UPA0bdKTW\"", ""),
                new Case(
                        "secrets/github-token",
                        "md",
                        "ghp_" + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                        ""),
                new Case("secrets/github-fine-grained-token", "md", "github_pat_" + "abc", ""),
                new Case("secrets/slack-token", "md", "xoxb-" + "<your-bot-token>", ""),
                new Case(
                        "secrets/slack-webhook-url",
                        "md",
                        "https://hooks.slack.com/services/"
                                + "T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX",
                        ""),
                // Stripe test-mode keys only work against test data
                new Case(
                        "secrets/stripe-secret-key",
                        "js",
                        "stripe('sk_test_" + "qe4ZEMIkxINHmzWZ23h9e3h6')",
                        ""),
                new Case("secrets/google-api-key", "json", "\"AIza" + "Hm0_wUhYk_euQ\"", ""),
                new Case(
                        "secrets/gcp-service-account-key",
                        "json",
                        "{\"private_key_id\": \"<key-id>\"}",
                        ""),
                // Code that assembles a PEM from pieces, public keys, certificates, and a
                // truncated documentation snippet
                new Case(
                        "secrets/private-key",
                        "java",
                        "String pem = \"-----BEGIN RSA PRIVATE KEY-----\\n\" + body.lines()"
                                + ".map(line -> line.strip() + \"\\n\").collect(joining())"
                                + " + \"-----END RSA PRIVATE KEY-----\";",
                        ""),
                new Case(
                        "secrets/private-key",
                        "pem",
                        "-----BEGIN PUBLIC KEY-----\n" + KEY_BODY + "-----END PUBLIC KEY-----\n",
                        ""),
                new Case(
                        "secrets/private-key",
                        "pem",
                        "-----BEGIN CERTIFICATE-----\n" + KEY_BODY + "-----END CERTIFICATE-----\n",
                        ""),
                new Case(
                        "secrets/private-key",
                        "md",
                        "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
                        ""),
                new Case("secrets/jwt", "md", "eyJ" + "hbGciOiJIUzI1NiJ9", ""),
                // Generic: placeholders, names, URLs, UUIDs, hashes, low entropy, wrong file type
                new Case("secrets/generic-secret", "properties", "db.password=${DB_PASSWORD}", ""),
                new Case("secrets/generic-secret", "properties", "admin.password=changeme", ""),
                new Case("secrets/generic-secret", "yml", "password: <your-password-here>", ""),
                new Case("secrets/generic-secret", "properties", "db.password=password123", ""),
                new Case(
                        "secrets/generic-secret",
                        "yml",
                        "token-uri: https://oauth2.googleapis.com/token",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "properties",
                        "security.token-filter=org.example.security.TokenFilter",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "properties",
                        "server.ssl.key-store-password-file=/etc/ssl/keystore-password.txt",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "properties",
                        "session.token-seed=550e8400-e29b-41d4-a716-446655440000",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "properties",
                        "token.sha256=73c38acbd8c2c72e9e1744217d0e180344619c12ec887a7574f4ffeb80881c0e",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "java",
                        "static final String TOKEN_ENV_VAR = \"GITHUB_TOKEN\";",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "java",
                        "String password = System.getenv(\"DB_PASSWORD\");",
                        ""),
                new Case(
                        "secrets/generic-secret",
                        "xml",
                        "<password>${env.MAVEN_PASSWORD}</password>",
                        ""),
                new Case("secrets/generic-secret", "md", "password=" + GENERIC, ""));
    }

    @ParameterizedTest
    @MethodSource("matches")
    void find_realSecret_reportsExactlyTheSecretValue(Case c) {
        assertThat(find(c)).containsExactly(c.secret());
    }

    @ParameterizedTest
    @MethodSource("nearMisses")
    void find_lookAlike_reportsNothing(Case c) {
        assertThat(find(c)).isEmpty();
    }

    @Test
    void bundled_everyRule_isAWellFormedSecurityRuleWithAtLeastOneMatchTest() {
        List<String> tested = matches().stream().map(Case::ruleId).distinct().toList();

        assertThat(RULES)
                .extracting(r -> r.rule().id())
                .doesNotHaveDuplicates()
                .allMatch(id -> id.startsWith("secrets/"))
                .containsExactlyInAnyOrderElementsOf(tested);
        assertThat(RULES)
                .extracting(SecretRule::rule)
                .extracting(Rule::category)
                .containsOnly(Category.SECURITY);
    }

    @Test
    void bundled_highestSeverityRules_areCloudKeysTokensAndPrivateKeys() {
        assertThat(RULES)
                .filteredOn(r -> r.rule().defaultSeverity() == Severity.CRITICAL)
                .extracting(r -> r.rule().id())
                .contains(
                        "secrets/aws-access-key-id", "secrets/github-token", "secrets/private-key");
    }

    @Test
    void parse_ruleWithoutPatterns_failsLoudly() {
        String yaml =
                """
                rules:
                  - id: secrets/empty
                    name: Empty
                    description: Nothing
                    severity: low
                    keywords: [x]
                """;

        assertThatThrownBy(() -> SecretRules.parse(YAMLMapper.builder().build().readTree(yaml)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secrets/empty needs patterns");
    }

    private static List<String> find(Case c) {
        SecretRule rule =
                RULES.stream()
                        .filter(r -> r.rule().id().equals(c.ruleId()))
                        .findFirst()
                        .orElseThrow();
        return rule.find(c.extension(), c.content(), c.content().toLowerCase(Locale.ROOT)).stream()
                .map(SecretRule.Match::secret)
                .toList();
    }
}
