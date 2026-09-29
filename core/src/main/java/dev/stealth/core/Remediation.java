package dev.stealth.core;

import java.util.Objects;
import java.util.Optional;

/** How to fix a finding. */
public record Remediation(Optional<String> fixedVersion, Optional<String> description) {

    public Remediation {
        Objects.requireNonNull(fixedVersion, "fixedVersion");
        Objects.requireNonNull(description, "description");
    }
}
