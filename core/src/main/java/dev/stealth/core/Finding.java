package dev.stealth.core;

import java.util.Objects;

/** A single problem reported by an {@link Analyzer}. */
public record Finding(String ruleId, String message) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(message, "message");
    }
}
