package dev.stealth.core.clean;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What re-resolving the dependency tree with a patch applied showed. Vulnerabilities are written
 * {@code groupId:artifactId ADVISORY-ID}.
 *
 * @param cleared targeted vulnerabilities gone from the resolved tree
 * @param remaining targeted vulnerabilities still there
 * @param introduced vulnerabilities that weren't there before the patch
 * @param problem why the patch couldn't be checked, e.g. the patched POMs didn't resolve
 */
public record Proof(
        Status status,
        List<String> cleared,
        List<String> remaining,
        List<String> introduced,
        Optional<String> problem) {

    public enum Status {
        /** Every targeted vulnerability is gone and none were introduced. */
        PROVEN,
        /** Some targeted vulnerabilities are gone and none were introduced. */
        PARTIAL,
        /** Nothing targeted is gone, something was introduced, or it couldn't be checked. */
        REJECTED
    }

    public Proof {
        Objects.requireNonNull(status, "status");
        cleared = List.copyOf(cleared);
        remaining = List.copyOf(remaining);
        introduced = List.copyOf(introduced);
        Objects.requireNonNull(problem, "problem");
    }

    public boolean accepted() {
        return status != Status.REJECTED;
    }

    static Proof failed(String problem) {
        return new Proof(Status.REJECTED, List.of(), List.of(), List.of(), Optional.of(problem));
    }
}
