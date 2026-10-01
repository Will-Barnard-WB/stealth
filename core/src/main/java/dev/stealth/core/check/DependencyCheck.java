package dev.stealth.core.check;

import dev.stealth.core.Finding;
import dev.stealth.core.deps.MaintenanceAnalyzer;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.deps.Versions;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything worth knowing about a Maven artifact before depending on it, without a repository: the
 * newest stable version, whether it's still maintained, and the known vulnerabilities in the
 * version asked about. For agents about to add or upgrade a dependency.
 */
public class DependencyCheck {

    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final MavenCentralClient central;
    private final MavenCentralSearch search;
    private final VulnerabilityAnalyzer vulnerabilities;
    private final Period staleAfter;
    private final Clock clock;

    public DependencyCheck(
            MavenCentralClient central,
            MavenCentralSearch search,
            VulnerabilityAnalyzer vulnerabilities,
            Period staleAfter,
            Clock clock) {
        this.central = Objects.requireNonNull(central, "central");
        this.search = Objects.requireNonNull(search, "search");
        this.vulnerabilities = Objects.requireNonNull(vulnerabilities, "vulnerabilities");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DependencyCheck(
            MavenCentralClient central,
            MavenCentralSearch search,
            VulnerabilityAnalyzer vulnerabilities,
            Clock clock) {
        this(central, search, vulnerabilities, MaintenanceAnalyzer.DEFAULT_STALE_AFTER, clock);
    }

    /**
     * What's known about an artifact.
     *
     * @param found whether Maven Central has the artifact at all
     * @param version the version checked for vulnerabilities: the one asked about, or the latest
     * @param latestVersion the newest stable release (same variant, such as {@code -jre})
     * @param latestReleased when {@code latestVersion} was published, when the search index knows
     * @param maintained false when the newest release is older than the staleness threshold; empty
     *     when the release date isn't known
     * @param vulnerabilities known vulnerabilities in {@code version}, most severe first
     * @param latestVulnerabilities how many known vulnerabilities {@code latestVersion} has, when
     *     it differs from {@code version}
     */
    public record Result(
            String groupId,
            String artifactId,
            boolean found,
            Optional<String> version,
            Optional<String> latestVersion,
            Optional<LocalDate> latestReleased,
            Optional<Boolean> maintained,
            List<Finding> vulnerabilities,
            Optional<Integer> latestVulnerabilities) {}

    /**
     * @param version the version to check; empty checks the latest
     * @throws IllegalArgumentException if a coordinate isn't a valid Maven coordinate
     * @throws IOException if Maven Central or OSV.dev can't be reached and nothing is cached
     */
    public Result check(String groupId, String artifactId, Optional<String> version)
            throws IOException, InterruptedException {
        requireCoordinate("groupId", groupId);
        requireCoordinate("artifactId", artifactId);
        version.ifPresent(v -> requireCoordinate("version", v));

        Optional<List<String>> versions = central.versions(groupId, artifactId);
        if (versions.isEmpty() || versions.get().isEmpty()) {
            List<Finding> found =
                    version.isPresent()
                            ? vulnerabilities.check(groupId, artifactId, version.get())
                            : List.of();
            return new Result(
                    groupId,
                    artifactId,
                    false,
                    version,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    found,
                    Optional.empty());
        }

        // From "0" every stable version is newer; from the version asked about, its variant counts
        Optional<String> latest =
                Versions.newerVersion(versions.get(), version.orElse("0"))
                        .or(() -> version.filter(versions.get()::contains));
        Optional<LocalDate> released = Optional.empty();
        if (latest.isPresent()) {
            released =
                    search.published(groupId, artifactId, latest.get())
                            .map(i -> LocalDate.ofInstant(i, ZoneOffset.UTC));
        }
        LocalDate cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minus(staleAfter);
        Optional<Boolean> maintained = released.map(date -> !date.isBefore(cutoff));

        Optional<String> checked = version.or(() -> latest);
        List<Finding> found =
                checked.isPresent()
                        ? vulnerabilities.check(groupId, artifactId, checked.get())
                        : List.of();
        Optional<Integer> latestFound = Optional.empty();
        if (latest.isPresent() && !latest.equals(checked)) {
            latestFound =
                    Optional.of(vulnerabilities.check(groupId, artifactId, latest.get()).size());
        }
        return new Result(
                groupId,
                artifactId,
                true,
                checked,
                latest,
                released,
                maintained,
                found,
                latestFound);
    }

    private static void requireCoordinate(String name, String value) {
        if (value == null || !COORDINATE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name
                            + " must be a Maven coordinate such as com.google.guava, got '"
                            + value
                            + "'");
        }
    }
}
