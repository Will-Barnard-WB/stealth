package dev.stealth.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-repo settings from {@code .stealth.yml}. A placeholder until the config ticket adds the rest
 * of ADR-0004; {@link #defaults()} is what a repo without the file gets.
 *
 * @param disabledAnalyzers ids of analyzers that don't run
 */
public record StealthConfig(Set<String> disabledAnalyzers, Eol eol, Secrets secrets) {

    public StealthConfig {
        disabledAnalyzers = Set.copyOf(disabledAnalyzers);
        Objects.requireNonNull(eol, "eol");
        Objects.requireNonNull(secrets, "secrets");
    }

    public StealthConfig(Set<String> disabledAnalyzers, Eol eol) {
        this(disabledAnalyzers, eol, Secrets.defaults());
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

    /**
     * The {@code allow.secrets} section: secrets the team has accepted, such as test keys or a key
     * that's already revoked. Findings they match are dropped.
     */
    public record Secrets(List<AllowedSecret> allow) {

        public Secrets {
            allow = List.copyOf(allow);
        }

        public static Secrets defaults() {
            return new Secrets(List.of());
        }
    }

    /**
     * One {@code allow.secrets} entry. It matches by path glob or by fingerprint, or both, in which
     * case a finding has to match both.
     *
     * @param path gitignore-style glob of the files it covers, relative to the repo root
     * @param fingerprint a finding's fingerprint, which accepts that one secret in that one file
     * @param rules secret rule ids it's limited to; empty means every secret rule
     * @param reason why the secret is accepted
     * @param expires the last day (UTC) the entry applies
     */
    public record AllowedSecret(
            Optional<PathGlob> path,
            Optional<String> fingerprint,
            Set<String> rules,
            String reason,
            Optional<LocalDate> expires) {

        public AllowedSecret {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fingerprint, "fingerprint");
            rules = Set.copyOf(rules);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(expires, "expires");
            if (path.isEmpty() && fingerprint.isEmpty()) {
                throw new IllegalArgumentException("an allowed secret needs a path or fingerprint");
            }
        }

        public static AllowedSecret path(String glob, String reason) {
            return new AllowedSecret(
                    Optional.of(PathGlob.of(glob)),
                    Optional.empty(),
                    Set.of(),
                    reason,
                    Optional.empty());
        }

        public static AllowedSecret fingerprint(String fingerprint, String reason) {
            return new AllowedSecret(
                    Optional.empty(), Optional.of(fingerprint), Set.of(), reason, Optional.empty());
        }

        /** Whether this entry accepts a finding, on {@code today}. */
        public boolean allows(Finding finding, LocalDate today) {
            if (expires.isPresent() && today.isAfter(expires.get())) {
                return false;
            }
            if (!rules.isEmpty() && !rules.contains(finding.ruleId())) {
                return false;
            }
            if (fingerprint.isPresent() && !fingerprint.get().equals(finding.fingerprint())) {
                return false;
            }
            return path.isEmpty()
                    || finding.location().path().map(p -> path.get().matches(p)).orElse(false);
        }
    }
}
