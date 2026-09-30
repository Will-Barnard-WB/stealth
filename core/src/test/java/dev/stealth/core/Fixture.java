package dev.stealth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The sample repos under {@code fixtures/}, with their expected findings in {@code
 * fixtures/README.md}. Fixtures are read-only: tests that modify files use {@link #copyTo(Path)}.
 */
public enum Fixture {
    BOOT2_LEGACY("boot2-legacy"),
    BOOT4_CLEAN("boot4-clean"),
    EOL_RUNTIME("eol-runtime"),
    WITH_SECRETS("with-secrets"),
    DUPLICATED("duplicated"),
    MULTI_MODULE("multi-module");

    private final String directoryName;

    Fixture(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return directoryName;
    }

    /** The fixture in the repo. Don't write to it. */
    public Path path() {
        return FixturesRoot.PATH.resolve(directoryName);
    }

    public RepoContext context() {
        return new RepoContext(path(), StealthConfig.defaults());
    }

    /**
     * Copies the fixture into {@code targetDir} (typically a {@code @TempDir}), skipping build
     * output in {@code target/}, and returns the copy's root.
     */
    public Path copyTo(Path targetDir) {
        Path copy = targetDir.resolve(directoryName);
        copyTree(path(), copy);
        return copy;
    }

    static void copyTree(Path source, Path copy) {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file :
                    files.filter(file -> !isBuildOutput(source.relativize(file))).toList()) {
                Files.copy(file, copy.resolve(source.relativize(file).toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't copy " + source, e);
        }
    }

    private static boolean isBuildOutput(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().equals("target")) {
                return true;
            }
        }
        return false;
    }

    /** Found once by walking up from the working directory, which Surefire sets to the module. */
    private static final class FixturesRoot {
        private static final Path PATH = find();

        private static Path find() {
            for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve("fixtures");
                if (Files.isRegularFile(candidate.resolve("README.md"))) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "No fixtures/ directory above " + Path.of("").toAbsolutePath());
        }
    }
}
