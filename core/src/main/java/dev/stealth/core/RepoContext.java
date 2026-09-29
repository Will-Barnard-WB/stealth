package dev.stealth.core;

import java.nio.file.Path;
import java.util.Objects;

/** The repository being analyzed. */
public record RepoContext(Path root) {

    public RepoContext {
        Objects.requireNonNull(root, "root");
    }
}
