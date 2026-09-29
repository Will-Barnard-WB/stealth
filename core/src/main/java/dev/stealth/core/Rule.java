package dev.stealth.core;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A kind of finding an {@link Analyzer} can report. Rule ids are {@code <analyzer>/<rule>} in
 * lowercase kebab-case and never change once released. See ADR-0001.
 */
public record Rule(
        String id,
        String name,
        String shortDescription,
        URI helpUri,
        Category category,
        Severity defaultSeverity) {

    private static final Pattern ID =
            Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*/[a-z0-9]+(-[a-z0-9]+)*");

    public Rule {
        requireValidId(id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shortDescription, "shortDescription");
        Objects.requireNonNull(helpUri, "helpUri");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
    }

    static void requireValidId(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        if (!ID.matcher(ruleId).matches()) {
            throw new IllegalArgumentException(
                    "rule id must be <analyzer>/<rule> in lowercase kebab-case: " + ruleId);
        }
    }
}
