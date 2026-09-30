package dev.stealth.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A gitignore-style glob over repo-relative paths with forward slashes, as used in {@code
 * .stealth.yml} (ADR-0004). Matching doesn't depend on the platform's path separator.
 *
 * <ul>
 *   <li>{@code *} matches within one path segment, {@code ?} one character in a segment
 *   <li>{@code **} matches across segments: {@code fixtures/**}, {@code **}{@code /generated/**}
 *   <li>a glob without a {@code /} matches a file or directory name at any depth: {@code *.pem}
 *   <li>a glob that matches a directory also matches everything under it
 * </ul>
 */
public final class PathGlob {

    private final String glob;
    private final Pattern pattern;

    private PathGlob(String glob, Pattern pattern) {
        this.glob = glob;
        this.pattern = pattern;
    }

    public static PathGlob of(String glob) {
        Objects.requireNonNull(glob, "glob");
        String trimmed = glob.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty glob");
        }
        String body = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        body = body.endsWith("/") ? body.substring(0, body.length() - 1) : body;
        boolean anchored = trimmed.startsWith("/") || body.contains("/");
        String regex = (anchored ? "" : "(?:.*/)?") + toRegex(body) + "(?:/.*)?";
        return new PathGlob(glob, Pattern.compile(regex));
    }

    /** Whether {@code path} (repo-relative, forward slashes) matches. */
    public boolean matches(String path) {
        return pattern.matcher(path).matches();
    }

    @Override
    public String toString() {
        return glob;
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                boolean slashFollows = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                regex.append(slashFollows ? "(?:.*/)?" : ".*");
                i += slashFollows ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
