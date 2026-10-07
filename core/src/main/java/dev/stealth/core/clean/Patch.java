package dev.stealth.core.clean;

import dev.stealth.core.deps.Versions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One POM edit that clears known vulnerabilities, and the proof that it does.
 *
 * @param edit the change to make
 * @param changes each vulnerable dependency the edit moves, from and to which version
 * @param alsoMoves other dependencies the same edit moves (they share the property), by {@code
 *     groupId:artifactId}
 * @param bom the BOM the edit moves as a whole ({@code groupId:artifactId}), keeping its artifacts
 *     on matching versions, e.g. {@code org.springframework:spring-framework-bom}
 * @param targets the vulnerabilities it's meant to clear, as {@code groupId:artifactId ADVISORY-ID}
 * @param crossesMajor whether a dependency moves to a new major version, which can break at
 *     runtime; such patches are never applied by default
 * @param level the biggest move among {@code changes}: a patch release, a minor or a major
 * @param proof what re-resolving the tree with the edit showed
 */
public record Patch(
        PomEdit edit,
        List<VersionChange> changes,
        List<String> alsoMoves,
        Optional<String> bom,
        List<String> targets,
        boolean crossesMajor,
        Versions.Update level,
        Proof proof) {

    public Patch {
        Objects.requireNonNull(edit, "edit");
        changes = List.copyOf(changes);
        alsoMoves = List.copyOf(alsoMoves);
        Objects.requireNonNull(bom, "bom");
        targets = List.copyOf(targets);
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(proof, "proof");
    }

    /** Proven or partly proven, and within the same major: safe to apply. */
    public boolean safe() {
        return proof.accepted() && !crossesMajor;
    }

    /** A dependency version moved by a patch. */
    public record VersionChange(String dependency, String from, String to) {}
}
