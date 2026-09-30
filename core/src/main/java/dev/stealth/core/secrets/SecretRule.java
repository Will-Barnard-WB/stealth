package dev.stealth.core.secrets;

import dev.stealth.core.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One kind of secret, from the bundled {@code rules.yml}: the {@link Rule} it reports and the
 * precompiled patterns that find it. Immutable, so one instance serves every scanning thread.
 *
 * @param keywords lowercase; a file that contains none of them isn't searched
 * @param minEntropy if present, values below it, and values that look like names, are skipped
 */
record SecretRule(
        Rule rule, List<String> keywords, OptionalDouble minEntropy, List<Detector> detectors) {

    SecretRule {
        Objects.requireNonNull(rule, "rule");
        keywords = keywords.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
        Objects.requireNonNull(minEntropy, "minEntropy");
        detectors = List.copyOf(detectors);
    }

    /**
     * One regex, limited to some file extensions.
     *
     * @param extensions lowercase, without the dot; empty means every file
     */
    record Detector(Pattern pattern, Set<String> extensions) {

        Detector {
            Objects.requireNonNull(pattern, "pattern");
            extensions = Set.copyOf(extensions);
        }

        boolean appliesTo(String extension) {
            return extensions.isEmpty() || extensions.contains(extension);
        }
    }

    /**
     * Where a secret is in a file's text.
     *
     * @param matchStart offset of the whole match, whose line is where the finding is reported: for
     *     a private key, the {@code BEGIN} line
     * @param start offset of the secret value, which may be after the start of the whole match
     */
    record Match(SecretRule rule, int matchStart, int start, int end, String secret) {

        boolean overlaps(Match other) {
            return start < other.end && other.start < end;
        }
    }

    /**
     * Finds this rule's secrets in a file, placeholders and low-entropy values already removed.
     *
     * @param lowerContent {@code content} in lowercase, shared by every rule for keyword checks
     */
    List<Match> find(String extension, String content, String lowerContent) {
        if (keywords.stream().noneMatch(lowerContent::contains)) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        for (Detector detector : detectors) {
            if (!detector.appliesTo(extension)) {
                continue;
            }
            Pattern pattern = detector.pattern();
            boolean hasSecret = pattern.namedGroups().containsKey("secret");
            boolean hasKey = pattern.namedGroups().containsKey("key");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String group = hasSecret ? "secret" : null;
                String secret = group == null ? matcher.group() : matcher.group(group);
                String key = hasKey && matcher.group("key") != null ? matcher.group("key") : "";
                if (accepts(secret, key)) {
                    int start = group == null ? matcher.start() : matcher.start(group);
                    int end = group == null ? matcher.end() : matcher.end(group);
                    matches.add(new Match(this, matcher.start(), start, end, secret));
                }
            }
        }
        return matches;
    }

    /** Whether a matched value is reported: the placeholder and entropy filters. */
    boolean accepts(String secret, String key) {
        if (SecretValues.isPlaceholder(secret)) {
            return false;
        }
        if (minEntropy.isEmpty()) {
            return true;
        }
        return !SecretValues.looksLikeName(secret, key)
                && SecretValues.entropy(secret) >= minEntropy.getAsDouble();
    }
}
