package dev.stealth.cli;

import dev.stealth.core.Advisory;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import dev.stealth.core.score.FixPlanner;
import dev.stealth.core.score.HealthScore;
import dev.stealth.core.score.HealthScore.CategoryScore;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.versioning.ComparableVersion;
import picocli.CommandLine.Help.Ansi;

/**
 * The {@code stealth doctor} report for a terminal: which analyzers ran, a summary per category,
 * then the fixes ranked most important first (see {@link FixPlanner}), each with what it deals
 * with.
 */
final class TerminalReport {

    /** How many fixes to show before pointing at {@code --all}. */
    static final int TOP = 10;

    private static final int COMPONENTS_LISTED = 5;

    /** Every non-ASCII character the report prints; without them it falls back to ASCII. */
    static final String UNICODE_SYMBOLS = "✓✗→·";

    private final Ansi ansi;
    private final boolean unicode;

    TerminalReport(Ansi ansi, boolean unicode) {
        this.ansi = ansi;
        this.unicode = unicode;
    }

    String render(Path root, DoctorReport report, boolean all) {
        return render(root, report, all, Set.of());
    }

    /**
     * @param severities the severities the findings were filtered to, which the report names when
     *     none are left; empty if they weren't filtered
     */
    String render(Path root, DoctorReport report, boolean all, Set<Severity> severities) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(style("bold", "stealth doctor") + "  " + displayPath(root));
        lines.add("  " + statuses(report.results()));
        for (AnalyzerResult result : report.results()) {
            if (result.status() == AnalyzerStatus.FAILED
                    || result.status() == AnalyzerStatus.TIMED_OUT) {
                lines.add(
                        "  "
                                + style(
                                        "bold,fg(203)",
                                        result.analyzerId() + " " + lower(result.status()))
                                + ": "
                                + result.error().orElse("")
                                + style("faint", "  (its findings are missing from this report)"));
            }
        }
        lines.add("");

        // The score always counts every finding; --critical and friends only narrow what's listed
        HealthScore score = report.score();
        List<Finding> findings =
                severities.isEmpty()
                        ? report.findings()
                        : report.findings().stream()
                                .filter(f -> severities.contains(f.severity()))
                                .toList();
        List<FixPlanner.Fix> fixes =
                report.fixes().stream()
                        .flatMap(
                                fix ->
                                        severities.isEmpty()
                                                ? Stream.of(fix)
                                                : fix.only(severities).stream())
                        .toList();

        lines.addAll(scoreLines(score, findings));
        lines.add("");

        if (findings.isEmpty()) {
            boolean incomplete =
                    report.results().stream().anyMatch(r -> r.status() != AnalyzerStatus.OK);
            String none =
                    severities.isEmpty()
                            ? "No problems found"
                            : "No "
                                    + severities.stream()
                                            .sorted()
                                            .map(TerminalReport::lower)
                                            .collect(Collectors.joining(" or "))
                                    + " findings";
            lines.add(
                    "  "
                            + (incomplete
                                    ? none + " by the analyzers that finished."
                                    : style("fg(114)", none + ".")));
            lines.add("");
            return String.join(System.lineSeparator(), lines) + System.lineSeparator();
        }

        lines.add("  " + style("bold,fg(209)", "Fix these first"));
        lines.add("");
        List<FixPlanner.Fix> shown = all ? fixes : fixes.subList(0, Math.min(TOP, fixes.size()));
        int locationWidth =
                shown.stream().mapToInt(fix -> location(fix.location()).length()).max().orElse(0);
        for (int i = 0; i < shown.size(); i++) {
            renderFix(lines, i + 1, shown.get(i), locationWidth, all);
        }

        String summary =
                findings.size()
                        + " "
                        + plural(findings.size(), "finding")
                        + " in "
                        + fixes.size()
                        + " "
                        + plural(fixes.size(), "fix", "fixes");
        if (!all) {
            summary +=
                    (fixes.size() > TOP ? separator() + "showing the top " + TOP : "")
                            + separator()
                            + "stealth doctor --all lists every finding";
        }
        lines.add("  " + style("faint", summary));
        lines.add("");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private void renderFix(
            List<String> lines, int rank, FixPlanner.Fix fix, int locationWidth, boolean all) {
        String indent = " ".repeat(2 + 2 + 2 + locationWidth + 3);
        lines.add(
                "  "
                        + style("bold", String.format("%2d", rank))
                        + "  "
                        + style("faint", pad(location(fix.location()), locationWidth))
                        + "   "
                        + title(fix));
        detail(fix).forEach(detail -> lines.add(indent + detail));
        if (all) {
            for (Finding finding : fix.findings()) {
                lines.add(indent + severity(finding.severity()) + "  " + finding.message());
            }
        }
        lines.add("");
    }

    /**
     * What to change: the dependency and the version to move to. For a line with no outdated
     * finding, the version that fixes every vulnerability found there.
     */
    private String title(FixPlanner.Fix fix) {
        Optional<Finding> outdated = outdated(fix);
        Optional<Component> component =
                outdated.or(() -> onlyDirectComponent(fix))
                        .flatMap(f -> Component.of(f.component()));
        if (component.isEmpty() && vulnerabilities(fix).isEmpty()) {
            // Neither outdated nor vulnerable, e.g. unmaintained: name the dependency, if there is
            // one
            Optional<Component> dependency = Component.of(fix.findings().getFirst().component());
            if (dependency.isPresent()) {
                return style("bold", dependency.get().artifactId())
                        + "  "
                        + dependency.get().version();
            }
        }
        if (component.isEmpty()) {
            return fix.findings().getFirst().message();
        }
        Optional<String> target =
                outdated.isPresent()
                        ? outdated.flatMap(f -> f.remediation()).flatMap(Remediation::fixedVersion)
                        : vulnerabilities(fix).stream()
                                .flatMap(
                                        f ->
                                                f
                                                        .remediation()
                                                        .flatMap(Remediation::fixedVersion)
                                                        .stream())
                                .max(Comparator.comparing(ComparableVersion::new));
        return style("bold", component.get().artifactId())
                + "  "
                + component.get().version()
                + target.map(t -> " " + arrow() + " " + style("fg(114)", t))
                        .orElse(style("faint", "  (no fixed version published)"));
    }

    /** The vulnerable dependency, if every vulnerability at this line is in the same one. */
    private static Optional<Finding> onlyDirectComponent(FixPlanner.Fix fix) {
        List<Finding> vulnerabilities = vulnerabilities(fix);
        boolean single =
                !vulnerabilities.isEmpty()
                        && vulnerabilities.stream().map(Finding::component).distinct().count() == 1;
        return single ? Optional.of(vulnerabilities.getFirst()) : Optional.empty();
    }

    /** One line on what the fix deals with, beyond the dependency being outdated. */
    private List<String> detail(FixPlanner.Fix fix) {
        List<Finding> vulnerabilities = vulnerabilities(fix);
        if (vulnerabilities.isEmpty()) {
            List<String> details = new ArrayList<>();
            outdated(fix)
                    .ifPresent(
                            f ->
                                    details.add(
                                            style(
                                                    "faint",
                                                    f.ruleId().replace("deps/outdated-", "")
                                                            + " update")));
            // Anything else about the dependency, such as "no release in 10 years", minus the
            // coordinates the title already shows
            fix.findings().stream()
                    .filter(f -> !f.ruleId().startsWith("deps/") && f.component().isPresent())
                    .forEach(f -> details.add(f.message().replaceFirst("^[^ :]+:[^ :]+: ", "")));
            return details;
        }
        String testOnly =
                vulnerabilities.stream().allMatch(f -> f.message().endsWith("[test scope]"))
                        ? style("faint", " (test dependencies only)")
                        : "";
        Map<String, Long> byComponent =
                vulnerabilities.stream()
                        .collect(
                                Collectors.groupingBy(
                                        f ->
                                                Component.of(f.component())
                                                        .map(Component::artifactId)
                                                        .orElse("?"),
                                        LinkedHashMap::new,
                                        Collectors.counting()));
        if (vulnerabilities.size() == 1) {
            Finding only = vulnerabilities.getFirst();
            return List.of(
                    style("bold," + color(only.severity()), only.severity().name())
                            + "  "
                            + displayId(only.advisory().orElseThrow())
                            + "  "
                            + advisoryTitle(only)
                            + testOnly);
        }
        String counts = vulnerabilities.size() + " vulnerabilities";
        if (byComponent.size() == 1) {
            return List.of(counts + ": " + severityCounts(vulnerabilities) + testOnly);
        }
        String components =
                byComponent.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(COMPONENTS_LISTED)
                        .map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", "));
        int more = byComponent.size() - COMPONENTS_LISTED;
        return List.of(
                counts + " come in through it: " + severityCounts(vulnerabilities) + testOnly,
                style("faint", components + (more > 0 ? ", +" + more + " more" : "")));
    }

    /** "Health 50 / 100" and a line per category with its score, grade and finding counts. */
    private List<String> scoreLines(HealthScore score, List<Finding> findings) {
        List<String> lines = new ArrayList<>();
        String overall =
                score.overall().isPresent()
                        ? scored(score.overall().getAsInt())
                                + (score.overallCapped()
                                        ? style("faint", "   capped at 50 by critical findings")
                                        : "")
                                + (incomplete(score)
                                        ? style("faint", "   incomplete: an analyzer failed")
                                        : "")
                        : style("faint", "-    needs both categories; some analyzers didn't run");
        lines.add("  " + style("bold", pad("Health", 10)) + " " + overall);
        for (Category category : List.of(Category.SECURITY, Category.TECH)) {
            CategoryScore categoryScore = score.category(category);
            String label = "  " + style("bold", pad(capitalized(category), 10)) + " ";
            if (categoryScore.status() == HealthScore.Status.NOT_RUN) {
                lines.add(label + style("faint", "not run"));
                continue;
            }
            List<Finding> inCategory =
                    findings.stream().filter(f -> f.category() == category).toList();
            lines.add(
                    label
                            + scored(categoryScore.score())
                            + (inCategory.isEmpty() ? "" : "   " + severityCounts(inCategory))
                            + (categoryScore.status() == HealthScore.Status.INCOMPLETE
                                    ? style("faint", "   incomplete: an analyzer failed")
                                    : ""));
        }
        return lines;
    }

    private static boolean incomplete(HealthScore score) {
        return score.security().status() == HealthScore.Status.INCOMPLETE
                || score.tech().status() == HealthScore.Status.INCOMPLETE;
    }

    /** The score and its grade, coloured by grade. */
    private String scored(int score) {
        String grade = HealthScore.grade(score);
        String color =
                switch (grade) {
                    case "A", "B" -> "fg(114)";
                    case "C" -> "fg(214)";
                    default -> "fg(203)";
                };
        return style("bold," + color, pad(String.valueOf(score), 3) + " " + grade);
    }

    /** The dependency-freshness finding in this fix, if the dependency is outdated. */
    private static Optional<Finding> outdated(FixPlanner.Fix fix) {
        return fix.findings().stream().filter(f -> f.ruleId().startsWith("deps/")).findFirst();
    }

    private static List<Finding> vulnerabilities(FixPlanner.Fix fix) {
        return fix.findings().stream().filter(f -> f.advisory().isPresent()).toList();
    }

    private String statuses(List<AnalyzerResult> results) {
        if (results.isEmpty()) {
            return style("faint", "no analyzers ran");
        }
        return results.stream()
                        .map(
                                r ->
                                        switch (r.status()) {
                                            case OK ->
                                                    style("fg(114)", unicode ? "✓" : "ok")
                                                            + " "
                                                            + r.analyzerId();
                                            case FAILED, TIMED_OUT ->
                                                    style("bold,fg(203)", unicode ? "✗" : "x")
                                                            + " "
                                                            + r.analyzerId();
                                            case SKIPPED -> style("faint", "- " + r.analyzerId());
                                        })
                        .collect(Collectors.joining("   "))
                + style(
                        "faint",
                        String.format(
                                Locale.ROOT,
                                "   %.1f s",
                                results.stream()
                                                .mapToLong(r -> r.duration().toMillis())
                                                .max()
                                                .orElse(0)
                                        / 1000.0));
    }

    private String severityCounts(List<Finding> findings) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        findings.forEach(f -> counts.merge(f.severity(), 1L, Long::sum));
        return counts.entrySet().stream()
                .map(e -> style(color(e.getKey()), e.getValue() + " " + lower(e.getKey())))
                .collect(Collectors.joining(style("faint", separator())));
    }

    private String severity(Severity severity) {
        return style("bold," + color(severity), pad(severity.name(), 8));
    }

    private static String color(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "fg(196)";
            case HIGH -> "fg(203)";
            case MEDIUM -> "fg(214)";
            case LOW -> "fg(75)";
            case INFO -> "faint";
        };
    }

    /** The CVE, which people recognise, or the primary id if there's no CVE. */
    private static String displayId(Advisory advisory) {
        return advisory.aliases().stream()
                .filter(a -> a.startsWith("CVE-"))
                .findFirst()
                .orElse(advisory.id());
    }

    /** The advisory's summary from the finding message: between the ids and "; fixed in". */
    private static String advisoryTitle(Finding finding) {
        String message = finding.message();
        String id = finding.advisory().orElseThrow().id();
        int start = message.indexOf(id);
        if (start < 0) {
            return message;
        }
        String rest =
                message.substring(start + id.length()).replaceFirst("^ \\(CVE-[^)]*\\)", "").trim();
        int end = rest.indexOf("; ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    private String location(Location location) {
        return location.path()
                .map(
                        path ->
                                path
                                        + location.line().stream()
                                                .mapToObj(line -> ":" + line)
                                                .findFirst()
                                                .orElse(""))
                .orElse("(repository)");
    }

    private static String displayPath(Path root) {
        String home = System.getProperty("user.home");
        String path = root.toString();
        return home != null && path.startsWith(home + File.separator)
                ? "~" + path.substring(home.length())
                : path;
    }

    private String separator() {
        return unicode ? " · " : ", ";
    }

    private String arrow() {
        return unicode ? "→" : "->";
    }

    private String style(String styles, String text) {
        return ansi.string("@|" + styles + " " + text.replace("|@", "| @") + "|@");
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String capitalized(Category category) {
        String name = lower(category);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String plural(long count, String word) {
        return plural(count, word, word + "s");
    }

    private static String plural(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /**
     * {@code groupId:artifactId@version} from a {@code pkg:maven/group/artifact@version} Package
     * URL.
     */
    private record Component(String artifactId, String version) {

        static Optional<Component> of(Optional<String> purl) {
            return purl.filter(p -> p.startsWith("pkg:maven/") && p.contains("@"))
                    .map(
                            p -> {
                                String coordinates =
                                        p.substring("pkg:maven/".length(), p.indexOf('@'));
                                return new Component(
                                        coordinates.substring(coordinates.lastIndexOf('/') + 1),
                                        p.substring(p.indexOf('@') + 1));
                            });
        }
    }
}
