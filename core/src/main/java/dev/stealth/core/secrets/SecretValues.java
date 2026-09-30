package dev.stealth.core.secrets;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Tests on a candidate secret value: is it a placeholder, a name, random enough? */
final class SecretValues {

    /** Found in documentation and templates, never in a real credential. */
    private static final List<String> PLACEHOLDERS =
            List.of(
                    "example",
                    "changeme",
                    "change_me",
                    "change-me",
                    "placeholder",
                    "dummy",
                    "redacted",
                    "your_",
                    "your-",
                    "xxxxxx",
                    "******",
                    "<",
                    ">",
                    "${",
                    "#{",
                    "{{",
                    "%(");

    private static final Pattern UUID =
            Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    /** An environment variable or constant name: {@code GITHUB_TOKEN}. */
    private static final Pattern UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * A qualified name: {@code org.example.TokenFilter}, {@code spring.security.oauth2}, {@code
     * classpath:keystore.jks}. Deliberately not {@code /}-separated, which base64 secrets are.
     */
    private static final Pattern DOTTED_NAME =
            Pattern.compile("[A-Za-z_$][\\w$-]*(?:[.:][A-Za-z_$][\\w$-]*)+");

    /** Keys that name a hash or checksum, whose hex values are published, not secret. */
    private static final Pattern HASH_KEY =
            Pattern.compile("(?i).*(?:checksum|sha\\d*|md5|hash|digest|fingerprint).*");

    private static final int VISIBLE_CHARACTERS = 4;
    private static final String MASK = "********";

    private SecretValues() {}

    static boolean isPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return PLACEHOLDERS.stream().anyMatch(lower::contains)
                || value.chars().distinct().count() == 1;
    }

    /**
     * Whether a value assigned to a password-like key is probably a reference to the secret rather
     * than the secret itself.
     */
    static boolean looksLikeName(String value, String key) {
        return value.contains("://")
                || value.startsWith("/")
                || value.startsWith("./")
                || value.startsWith("~/")
                || UPPER_SNAKE.matcher(value).matches()
                || DOTTED_NAME.matcher(value).matches()
                || UUID.matcher(value).matches()
                || (HEX.matcher(value).matches() && HASH_KEY.matcher(key).matches());
    }

    /** Shannon entropy in bits per character. */
    static double entropy(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        value.codePoints().forEach(c -> counts.merge(c, 1, Integer::sum));
        double length = value.codePointCount(0, value.length());
        double entropy = 0;
        for (int count : counts.values()) {
            double p = count / length;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /**
     * The only form of a secret that ever leaves the analyzer: its first four characters (fewer for
     * a short value) and a fixed-length mask, which doesn't give away the length either.
     */
    static String mask(String value) {
        int visible = Math.min(VISIBLE_CHARACTERS, value.length() / 3);
        StringBuilder masked = new StringBuilder();
        // A preview must stay on one line of output, whatever the rule matched
        value.substring(0, visible)
                .chars()
                .forEach(c -> masked.append(c < 0x21 || c > 0x7e ? '?' : (char) c));
        return masked.append(MASK).toString();
    }
}
