package dev.stealth.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where a finding is. Paths are repo-relative with forward slashes and never absolute, since output
 * gets shared and uploaded.
 *
 * @param path the file, or empty for repo-level findings
 * @param line 1-based line in {@code path}
 * @param module repo-relative module directory ({@code ""} for the root module)
 */
public record Location(Optional<String> path, OptionalInt line, Optional<String> module) {

    public Location {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(module, "module");
        path.ifPresent(Location::requireRepoRelative);
        module.ifPresent(Location::requireRepoRelative);
        if (line.isPresent() && line.getAsInt() < 1) {
            throw new IllegalArgumentException("line must be 1-based: " + line.getAsInt());
        }
        if (line.isPresent() && path.isEmpty()) {
            throw new IllegalArgumentException("line without a path");
        }
    }

    /** A finding about the repository as a whole, such as a missing CODEOWNERS file. */
    public static Location repository() {
        return new Location(Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    /** A finding at a line of a file. */
    public static Location file(String path, int line) {
        return new Location(Optional.of(path), OptionalInt.of(line), Optional.empty());
    }

    private static void requireRepoRelative(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(
                    "path must be repo-relative with forward slashes: " + path);
        }
    }
}
