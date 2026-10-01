package dev.stealth.core;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Per-repo settings from {@code .stealth.yml} (ADR-0004), read by {@code StealthConfigLoader}.
 * {@link #defaults()} is what a repo without the file gets.
 *
 * @param disabledAnalyzers ids of analyzers that don't run ({@code analyzers.<id>.enabled: false})
 * @param ignore paths no analyzer looks at, as gitignore-style globs
 * @param severity rule id → overridden severity; empty means {@code off}, which drops the rule
 * @param allowedDependencies the {@code allow.dependencies} section
 * @param thresholds analyzer settings from the {@code analyzers} section
 * @param failUnder the CI gate's minimum scores
 */
public record StealthConfig(
        Set<String> disabledAnalyzers,
        Eol eol,
        Secrets secrets,
        List<PathGlob> ignore,
        Map<String, Optional<Severity>> severity,
        List<AllowedDependency> allowedDependencies,
        Thresholds thresholds,
        FailUnder failUnder) {

    public StealthConfig {
        disabledAnalyzers = Set.copyOf(disabledAnalyzers);
        Objects.requireNonNull(eol, "eol");
        Objects.requireNonNull(secrets, "secrets");
        ignore = List.copyOf(ignore);
        severity = Map.copyOf(severity);
        allowedDependencies = List.copyOf(allowedDependencies);
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(failUnder, "failUnder");
    }

    public StealthConfig(Set<String> disabledAnalyzers, Eol eol, Secrets secrets) {
        this(
                disabledAnalyzers,
                eol,
                secrets,
                List.of(),
                Map.of(),
                List.of(),
                Thresholds.defaults(),
                FailUnder.none());
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

    /** Whether {@code path} (repo-relative, forward slashes) is excluded by {@code ignore}. */
    public boolean isIgnored(String path) {
        return !path.isEmpty() && ignore.stream().anyMatch(glob -> glob.matches(path));
    }

    /** Findings after the config is applied, and anything worth telling the user about it. */
    public record Applied(List<Finding> findings, List<String> warnings) {}

    /**
     * Steps 3 and 4 of ADR-0004's order of application: severity overrides ({@code off} drops the
     * finding), then allowlists. Findings in ignored paths are dropped too, for analyzers that
     * report on files they didn't list themselves.
     */
    public Applied apply(List<Finding> findings, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        for (AllowedSecret entry : secrets.allow()) {
            entry.expires()
                    .filter(today::isAfter)
                    .ifPresent(
                            d ->
                                    warnings.add(
                                            "allow.secrets entry expired on "
                                                    + d
                                                    + ": "
                                                    + entry.reason()));
        }
        for (AllowedDependency entry : allowedDependencies) {
            entry.expires()
                    .filter(today::isAfter)
                    .ifPresent(
                            d ->
                                    warnings.add(
                                            "allow.dependencies entry expired on "
                                                    + d
                                                    + ": "
                                                    + entry.reason()));
        }
        List<Finding> kept = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.location().path().map(this::isIgnored).orElse(false)) {
                continue;
            }
            Optional<Severity> override = severity.get(finding.ruleId());
            Finding effective = finding;
            if (override != null) {
                if (override.isEmpty()) {
                    continue; // off
                }
                effective = withSeverity(finding, override.get());
            }
            Finding candidate = effective;
            boolean allowed =
                    secrets.allow().stream().anyMatch(e -> e.allows(candidate, today))
                            || allowedDependencies.stream()
                                    .anyMatch(e -> e.allows(candidate, today));
            if (!allowed) {
                kept.add(effective);
            }
        }
        return new Applied(List.copyOf(kept), List.copyOf(warnings));
    }

    private static Finding withSeverity(Finding finding, Severity severity) {
        return new Finding(
                finding.ruleId(),
                finding.category(),
                severity,
                finding.message(),
                finding.location(),
                finding.component(),
                finding.advisory(),
                finding.remediation(),
                finding.fingerprint());
    }

    /**
     * Analyzer settings from the {@code analyzers} section. Empty values fall back to the
     * analyzer's defaults (or the {@code stealth.*} properties).
     */
    public record Thresholds(
            Optional<Period> maintenanceStaleAfter,
            OptionalInt duplicationMinTokens,
            Optional<Boolean> duplicationIncludeTests,
            Optional<Period> hygieneStaleBranchAfter,
            OptionalLong hygieneLargeFileBytes) {

        public static Thresholds defaults() {
            return new Thresholds(
                    Optional.empty(),
                    OptionalInt.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.empty());
        }
    }

    /**
     * The CI gate (ADR-0004 {@code fail-under}): minimum scores, each optional. {@code --fail-under
     * N} on the command line replaces {@code overall}.
     */
    public record FailUnder(OptionalInt overall, OptionalInt security, OptionalInt tech) {

        public static FailUnder none() {
            return new FailUnder(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty());
        }

        public FailUnder withOverall(int overall) {
            return new FailUnder(OptionalInt.of(overall), security, tech);
        }

        public boolean isSet() {
            return overall.isPresent() || security.isPresent() || tech.isPresent();
        }
    }

    /**
     * One {@code allow.dependencies} entry: matches findings about a component (Package URL, any
     * version unless it has {@code @version}) and/or an advisory (by id or alias).
     */
    public record AllowedDependency(
            Optional<String> component,
            Optional<String> advisory,
            Set<String> rules,
            String reason,
            Optional<LocalDate> expires) {

        public AllowedDependency {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(advisory, "advisory");
            rules = Set.copyOf(rules);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(expires, "expires");
            if (component.isEmpty() && advisory.isEmpty()) {
                throw new IllegalArgumentException(
                        "an allowed dependency needs a component or an advisory");
            }
        }

        public boolean allows(Finding finding, LocalDate today) {
            if (expires.isPresent() && today.isAfter(expires.get())) {
                return false;
            }
            if (!rules.isEmpty() && !rules.contains(finding.ruleId())) {
                return false;
            }
            if (component.isPresent() && !matchesComponent(finding.component())) {
                return false;
            }
            return advisory.isEmpty()
                    || finding.advisory()
                            .map(
                                    a ->
                                            a.id().equals(advisory.get())
                                                    || a.aliases().contains(advisory.get()))
                            .orElse(false);
        }

        private boolean matchesComponent(Optional<String> purl) {
            if (purl.isEmpty()) {
                return false;
            }
            String wanted = component.get();
            return wanted.contains("@")
                    ? purl.get().equals(wanted)
                    : purl.get().equals(wanted) || purl.get().startsWith(wanted + "@");
        }
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
