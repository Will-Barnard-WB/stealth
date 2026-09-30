package dev.stealth.core.deps;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Deciding whether a newer version exists, conservatively: a false "outdated" costs more trust than
 * a missed one. So pre-releases, date-stamped relics and other flavours of the same library don't
 * count as upgrades.
 */
public final class Versions {

    /** How far behind a version is. */
    public enum Update {
        MAJOR,
        MINOR,
        PATCH
    }

    private static final Pattern PRE_RELEASE_TOKEN =
            Pattern.compile(
                    "(alpha|beta|milestone|preview|pre|rc|cr|ea|dev|snapshot)\\d*|[abm]\\d+");

    // Guava-style variants published side by side: 33.0.0-jre and 33.0.0-android
    private static final Pattern VARIANT_TOKEN = Pattern.compile("jre|android|jdk\\d*|java\\d*");

    // commons-collections 20040616 and friends: sorts above every real release, but isn't one
    private static final Pattern DATE_STAMP = Pattern.compile("(19|20)\\d{6}([.\\-].*)?");

    private static final Pattern LEADING_NUMBERS =
            Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private Versions() {}

    /**
     * The newest version in {@code available} that {@code current} could upgrade to, if it's newer
     * than {@code current}. Pre-releases only count when {@code current} is a pre-release itself.
     */
    public static Optional<String> newerVersion(Collection<String> available, String current) {
        boolean currentIsPreRelease = isPreRelease(current);
        boolean currentIsDateStamp = isDateStamp(current);
        Optional<String> currentVariant = variant(current);
        ComparableVersion currentVersion = new ComparableVersion(current);
        return available.stream()
                .filter(v -> currentIsPreRelease || !isPreRelease(v))
                .filter(v -> currentIsDateStamp || !isDateStamp(v))
                .filter(v -> currentVariant.isEmpty() || currentVariant.equals(variant(v)))
                .max(Comparator.comparing(ComparableVersion::new))
                .filter(latest -> new ComparableVersion(latest).compareTo(currentVersion) > 0);
    }

    public static boolean isPreRelease(String version) {
        return tokens(version).stream().anyMatch(t -> PRE_RELEASE_TOKEN.matcher(t).matches());
    }

    static boolean isDateStamp(String version) {
        return DATE_STAMP.matcher(version).matches();
    }

    /** The variant qualifier, such as {@code jre} in {@code 33.0.0-jre}. */
    public static Optional<String> variant(String version) {
        return tokens(version).stream().filter(t -> VARIANT_TOKEN.matcher(t).matches()).findFirst();
    }

    /** Which part of the version number changes from {@code current} to {@code latest}. */
    public static Update update(String current, String latest) {
        int[] from = leadingNumbers(current);
        int[] to = leadingNumbers(latest);
        if (from[0] != to[0]) {
            return Update.MAJOR;
        }
        return from[1] != to[1] ? Update.MINOR : Update.PATCH;
    }

    private static int[] leadingNumbers(String version) {
        int[] numbers = new int[3];
        Matcher matcher = LEADING_NUMBERS.matcher(version);
        if (matcher.find()) {
            for (int i = 0; i < 3; i++) {
                String group = matcher.group(i + 1);
                numbers[i] = group == null ? 0 : Integer.parseInt(group);
            }
        }
        return numbers;
    }

    private static List<String> tokens(String version) {
        return Arrays.asList(version.toLowerCase(Locale.ROOT).split("[.\\-_]"));
    }
}
