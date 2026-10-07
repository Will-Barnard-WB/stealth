package dev.stealth.core.clean;

import dev.stealth.core.Finding;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code stealth clean} can do about a repository's vulnerabilities.
 *
 * @param patches every candidate patch, safe ones first
 * @param combined the proof for the safe patches applied together, when there are any
 * @param remaining vulnerability findings no safe patch clears: they need a major upgrade
 *     (migration), or have no fixed version yet
 * @param secrets secret findings, which need the developer (rotate) and the agent (move to config)
 */
public record CleanupPlan(
        List<Patch> patches,
        Optional<Proof> combined,
        List<Finding> remaining,
        List<Finding> secrets) {

    public CleanupPlan {
        patches = List.copyOf(patches);
        Objects.requireNonNull(combined, "combined");
        remaining = List.copyOf(remaining);
        secrets = List.copyOf(secrets);
    }

    public List<Patch> safePatches() {
        return patches.stream().filter(Patch::safe).toList();
    }
}
