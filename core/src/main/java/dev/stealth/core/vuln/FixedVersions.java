package dev.stealth.core.vuln;

import dev.stealth.core.deps.Versions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.maven.artifact.versioning.ComparableVersion;

/** Which version fixes an advisory for the version in use, from the advisory's ranges. */
final class FixedVersions {

    private FixedVersions() {}

    /**
     * The lowest version that fixes {@code advisory} for {@code name} at {@code version}: the
     * {@code fixed} event closing the range {@code version} is in. Empty if no fix is published
     * (the range is open, or ends in {@code last_affected}). Ranges for other packages, such as
     * repackaged copies, are ignored.
     */
    static Optional<String> fixedVersion(OsvVulnerability advisory, String name, String version) {
        ComparableVersion current = new ComparableVersion(version);
        List<Interval> intervals =
                advisory.affected().stream()
                        .filter(a -> "Maven".equals(a.ecosystem()) && name.equals(a.name()))
                        .flatMap(a -> a.ranges().stream())
                        .flatMap(range -> intervals(range).stream())
                        .toList();
        List<Interval> containing = intervals.stream().filter(i -> i.contains(current)).toList();
        // OSV matched this version, so it's affected even when no range contains it (loosely
        // written advisories); then the best answer is the next fix above it
        List<Interval> relevant = containing.isEmpty() ? intervals : containing;
        if (!containing.isEmpty() && containing.stream().allMatch(i -> i.fixed().isEmpty())) {
            return Optional.empty();
        }
        return relevant.stream()
                .flatMap(i -> i.fixed().stream())
                .filter(fixed -> new ComparableVersion(fixed).compareTo(current) > 0)
                .min(Comparator.comparing(ComparableVersion::new))
                .map(fixed -> sameVariant(fixed, version));
    }

    /**
     * {@code 32.0.0-android} becomes {@code 32.0.0-jre} for a project on {@code 30.1-jre}:
     * advisories often list one variant's fix, but the same release fixes both.
     */
    static String sameVariant(String fixed, String current) {
        Optional<String> fixedVariant = Versions.variant(fixed);
        Optional<String> currentVariant = Versions.variant(current);
        if (fixedVariant.isEmpty()
                || currentVariant.isEmpty()
                || fixedVariant.equals(currentVariant)) {
            return fixed;
        }
        int at = fixed.toLowerCase(Locale.ROOT).lastIndexOf(fixedVariant.get());
        return fixed.substring(0, at)
                + currentVariant.get()
                + fixed.substring(at + fixedVariant.get().length());
    }

    /**
     * A range's events as intervals: each {@code introduced} opens one, the next {@code fixed},
     * {@code last_affected} or {@code limit} closes it.
     */
    private static List<Interval> intervals(OsvVulnerability.Range range) {
        List<Interval> intervals = new ArrayList<>();
        String introduced = null;
        for (OsvVulnerability.Event event : range.events()) {
            switch (event.kind()) {
                case "introduced" -> {
                    if (introduced != null) {
                        intervals.add(new Interval(introduced, Optional.empty(), Optional.empty()));
                    }
                    introduced = event.version();
                }
                case "fixed", "limit" -> {
                    intervals.add(
                            new Interval(
                                    introduced == null ? "0" : introduced,
                                    Optional.of(event.version()),
                                    Optional.empty()));
                    introduced = null;
                }
                case "last_affected" -> {
                    intervals.add(
                            new Interval(
                                    introduced == null ? "0" : introduced,
                                    Optional.empty(),
                                    Optional.of(event.version())));
                    introduced = null;
                }
                default -> {
                    // Unknown event kinds are ignored, as the OSV schema asks
                }
            }
        }
        if (introduced != null) {
            intervals.add(new Interval(introduced, Optional.empty(), Optional.empty()));
        }
        return intervals;
    }

    /** {@code [introduced, fixed)} or {@code [introduced, lastAffected]}; open if neither. */
    private record Interval(
            String introduced, Optional<String> fixed, Optional<String> lastAffected) {

        boolean contains(ComparableVersion version) {
            if (!"0".equals(introduced)
                    && version.compareTo(new ComparableVersion(introduced)) < 0) {
                return false;
            }
            if (fixed.isPresent()) {
                return version.compareTo(new ComparableVersion(fixed.get())) < 0;
            }
            return lastAffected.isEmpty()
                    || version.compareTo(new ComparableVersion(lastAffected.get())) <= 0;
        }
    }
}
