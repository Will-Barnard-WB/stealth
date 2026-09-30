package dev.stealth.core;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-repo settings from {@code .stealth.yml}. A placeholder until the config ticket adds the rest
 * of ADR-0004; {@link #defaults()} is what a repo without the file gets.
 *
 * @param disabledAnalyzers ids of analyzers that don't run
 */
public record StealthConfig(Set<String> disabledAnalyzers, Eol eol) {

    public StealthConfig {
        disabledAnalyzers = Set.copyOf(disabledAnalyzers);
        Objects.requireNonNull(eol, "eol");
    }

    public StealthConfig(Set<String> disabledAnalyzers) {
        this(disabledAnalyzers, Eol.defaults());
    }

    public static StealthConfig defaults() {
        return new StealthConfig(Set.of());
    }

    public boolean isEnabled(String analyzerId) {
        return !disabledAnalyzers.contains(analyzerId);
    }

    /**
     * The {@code eol} section. End-of-life dates differ between JDK distributions, and the Java
     * release a repo compiles for isn't necessarily the one it runs on, so both are configurable.
     *
     * @param javaDistribution the endoflife.date product whose dates apply, e.g. {@code oracle-jdk}
     * @param javaVersion the Java release the repo runs on, when it isn't the compile target
     */
    public record Eol(String javaDistribution, Optional<String> javaVersion) {

        public static final String DEFAULT_JAVA_DISTRIBUTION = "eclipse-temurin";

        public Eol {
            Objects.requireNonNull(javaDistribution, "javaDistribution");
            Objects.requireNonNull(javaVersion, "javaVersion");
        }

        public static Eol defaults() {
            return new Eol(DEFAULT_JAVA_DISTRIBUTION, Optional.empty());
        }

        public static Eol javaDistribution(String javaDistribution) {
            return new Eol(javaDistribution, Optional.empty());
        }
    }
}
