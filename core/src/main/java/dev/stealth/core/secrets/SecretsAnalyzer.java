package dev.stealth.core.secrets;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Rule;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.git.WorkingTreeFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Reports credentials committed to the working tree: cloud keys, tokens, private keys, and
 * high-entropy values assigned to password-like settings. Precision comes first, since a noisy
 * secrets scanner gets switched off: placeholders, names and low-entropy values aren't reported.
 *
 * <p>The secret itself never leaves this class. Findings carry a masked preview, and the
 * fingerprint hashes the value (ADR-0001: path + sha256 of the value).
 *
 * <p>Suppressing a finding: {@code stealth:allow} anywhere on its line, or an {@code allow.secrets}
 * entry in {@code .stealth.yml} by path glob or fingerprint.
 */
public class SecretsAnalyzer implements Analyzer {

    /**
     * Suppresses a secret when it's on the secret's line, in any comment syntax, or on a comment
     * line directly above it: {@code .properties} and PEM files have no end-of-line comments.
     */
    static final String INLINE_ALLOW = "stealth:allow";

    private static final Pattern COMMENT_LINE =
            Pattern.compile("\\s*(?:#|//|/\\*|\\*|<!--|--|;|!).*");

    /** Larger files are almost always generated or data, and slow to search. */
    static final long MAX_FILE_SIZE = 1024 * 1024;

    /** Git's own heuristic: a NUL byte in the first 8000 bytes means binary. */
    private static final int BINARY_CHECK_BYTES = 8000;

    /** Full of hashes that look random, and never hand-written. */
    private static final Set<String> LOCKFILES =
            Set.of(
                    "package-lock.json",
                    "npm-shrinkwrap.json",
                    "yarn.lock",
                    "pnpm-lock.yaml",
                    "bun.lock",
                    "gradle.lockfile",
                    "Gemfile.lock",
                    "Cargo.lock",
                    "composer.lock",
                    "poetry.lock",
                    "Pipfile.lock",
                    "go.sum");

    private static final int CONCURRENT_FILES =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final String ADVICE =
            "Revoke and rotate it, then load it from the environment or a secret store."
                    + " Deleting it from the file doesn't remove it from git history.";

    private final List<SecretRule> secretRules;
    private final Clock clock;

    public SecretsAnalyzer(Clock clock) {
        this(SecretRules.bundled(), clock);
    }

    SecretsAnalyzer(List<SecretRule> secretRules, Clock clock) {
        this.secretRules = List.copyOf(secretRules);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "secrets";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Rule> rules() {
        return secretRules.stream().map(SecretRule::rule).toList();
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        List<String> paths =
                context.get(WorkingTreeFiles.FILES).stream()
                        .filter(SecretsAnalyzer::isScannable)
                        .toList();
        List<Finding> findings = scan(context.root(), paths);

        List<StealthConfig.AllowedSecret> allowed = context.config().secrets().allow();
        LocalDate today = LocalDate.now(clock);
        return findings.stream()
                .filter(f -> allowed.stream().noneMatch(entry -> entry.allows(f, today)))
                .sorted(
                        Comparator.comparing((Finding f) -> f.location().path().orElse(""))
                                .thenComparingInt(f -> f.location().line().orElse(0))
                                .thenComparing(Finding::ruleId))
                .toList();
    }

    private static boolean isScannable(String path) {
        return !LOCKFILES.contains(fileName(path));
    }

    /** Files are read and searched in parallel; the rules are immutable and shared. */
    private List<Finding> scan(Path root, List<String> paths)
            throws IOException, InterruptedException {
        List<Future<List<Finding>>> scans = new ArrayList<>();
        try (ExecutorService executor =
                Executors.newFixedThreadPool(
                        CONCURRENT_FILES, Thread.ofVirtual().name("secrets-scan-", 0).factory())) {
            for (String path : paths) {
                scans.add(executor.submit(() -> scan(root, path)));
            }
            List<Finding> findings = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < scans.size(); i++) {
                try {
                    findings.addAll(scans.get(i).get());
                } catch (ExecutionException e) {
                    failures.add(paths.get(i) + ": " + e.getCause().getMessage());
                }
            }
            // A file we couldn't read might hold a secret, and leaving it out would read as
            // "no secrets there", so a partial scan fails the analyzer instead
            if (!failures.isEmpty()) {
                throw new IOException(
                        "couldn't read "
                                + failures.size()
                                + " of "
                                + paths.size()
                                + " files: "
                                + failures.getFirst());
            }
            return findings;
        }
    }

    private List<Finding> scan(Path root, String path) throws IOException {
        Optional<String> content = read(root.resolve(path));
        if (content.isEmpty()) {
            return List.of();
        }
        String text = content.get();
        String lower = text.toLowerCase(Locale.ROOT);
        String extension = extension(path);

        // Earlier rules are more specific, so a GitHub token assigned to TOKEN is reported as a
        // GitHub token and not again as a generic secret
        List<SecretRule.Match> kept = new ArrayList<>();
        for (SecretRule rule : secretRules) {
            for (SecretRule.Match match : rule.find(extension, text, lower)) {
                if (kept.stream().noneMatch(match::overlaps)) {
                    kept.add(match);
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (SecretRule.Match match : kept) {
            if (!allowedInline(text, match.matchStart())) {
                findings.add(finding(path, lineOf(text, match.matchStart()), match));
            }
        }
        return findings;
    }

    /** The file as text, or empty if it's too large, binary, or gone since it was listed. */
    private static Optional<String> read(Path file) throws IOException {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return Optional.empty();
            }
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        for (int i = 0; i < Math.min(bytes.length, BINARY_CHECK_BYTES); i++) {
            if (bytes[i] == 0) {
                return Optional.empty();
            }
        }
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Finding finding(String path, int line, SecretRule.Match match) {
        Rule rule = match.rule().rule();
        return new Finding(
                rule.id(),
                rule.category(),
                rule.defaultSeverity(),
                rule.name() + " (" + SecretValues.mask(match.secret()) + ") is hardcoded.",
                Location.file(path, line),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Remediation(Optional.empty(), Optional.of(ADVICE))),
                Fingerprints.of(rule.id(), path, sha256(match.secret())));
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static boolean allowedInline(String text, int offset) {
        int start = offset == 0 ? 0 : text.lastIndexOf('\n', offset - 1) + 1;
        int end = text.indexOf('\n', offset);
        if (text.substring(start, end < 0 ? text.length() : end).contains(INLINE_ALLOW)) {
            return true;
        }
        if (start == 0) {
            return false;
        }
        int previousStart = start == 1 ? 0 : text.lastIndexOf('\n', start - 2) + 1;
        String previous = text.substring(previousStart, start - 1);
        return previous.contains(INLINE_ALLOW) && COMMENT_LINE.matcher(previous).matches();
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
