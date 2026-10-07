package dev.stealth.cli;

import dev.stealth.core.Finding;
import dev.stealth.core.Remediation;
import dev.stealth.core.clean.CleanupPlan;
import dev.stealth.core.clean.CleanupResult;
import dev.stealth.core.clean.Patch;
import dev.stealth.core.clean.Verification;
import dev.stealth.core.deps.Versions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Help.Ansi;

/** {@code stealth clean}'s terminal output: the plan, what apply did, and verification. */
class CleanReport {

    private final Ansi ansi;

    CleanReport(Ansi ansi) {
        this.ansi = ansi;
    }

    String plan(Path root, CleanupPlan plan) {
        List<String> lines = header(root);
        List<Patch> safe = plan.safePatches();
        if (plan.vulnerabilities() == 0) {
            lines.add("  " + style("fg(114)", "No known vulnerabilities."));
        } else {
            String cleared =
                    plan.combined()
                            .map(
                                    c ->
                                            safe.size()
                                                    + plural(
                                                            safe.size(),
                                                            " safe patch",
                                                            " safe patches")
                                                    + " clear "
                                                    + c.cleared().size()
                                                    + " of them, proven together on the re-resolved"
                                                    + " dependency tree")
                            .orElse("no safe patches");
            lines.add(
                    "  "
                            + style("bold", plan.vulnerabilities() + " known vulnerabilities")
                            + style("faint", " · ")
                            + cleared);
        }
        lines.add("");

        if (!safe.isEmpty()) {
            lines.add(
                    "  "
                            + style("bold,fg(114)", "Safe patches")
                            + style("faint", "  stealth clean --apply puts these on a new branch"));
            int n = 1;
            for (Patch patch : safe) {
                lines.add(patchLine(String.format("%2d", n++), patch));
                detail(patch).forEach(d -> lines.add("      " + style("faint", d)));
            }
            lines.add("");
        }
        List<Patch> major =
                plan.patches().stream()
                        .filter(p -> p.crossesMajor() && p.proof().accepted())
                        .toList();
        if (!major.isEmpty()) {
            lines.add(
                    "  "
                            + style("bold,fg(214)", "Needs a major version")
                            + style("faint", "  proven, but only applied with --allow-major"));
            for (Patch patch : major) {
                lines.add(patchLine(" -", patch));
            }
            lines.add("");
        }
        if (!plan.remaining().isEmpty()) {
            lines.add(
                    "  "
                            + style("bold", "Not fixable by a version change here")
                            + style("faint", "  (" + plan.remaining().size() + ")"));
            remainingByDependency(plan.remaining()).forEach(l -> lines.add("      " + l));
            lines.add("");
        }
        if (!plan.secrets().isEmpty()) {
            lines.add(
                    "  "
                            + style("bold,fg(203)", "Secrets")
                            + style("faint", "  rotate them, then move them out of code"));
            for (Finding secret : plan.secrets()) {
                lines.add("      " + where(secret) + "  " + secret.message());
            }
            lines.add("");
        }
        if (!safe.isEmpty()) {
            lines.add(
                    "  "
                            + style("faint", "Next: ")
                            + "stealth clean --apply"
                            + style(
                                    "faint",
                                    "  (tests run before and after; your checkout isn't touched)"));
            lines.add("");
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    String result(Path root, CleanupResult result) {
        List<String> lines = header(root);
        if (result.branch().isEmpty()) {
            lines.add("  Nothing was applied.");
        } else {
            String base = result.base().substring(0, Math.min(10, result.base().length()));
            lines.add(
                    "  Branch "
                            + style("bold", result.branch().get())
                            + style("faint", " from " + base + "; your checkout is unchanged"));
            lines.add(
                    "  "
                            + style("fg(114)", "✓")
                            + " "
                            + result.applied().size()
                            + plural(result.applied().size(), " patch", " patches")
                            + " committed"
                            + result.after()
                                    .map(
                                            run ->
                                                    ", tests "
                                                            + (run.passed()
                                                                    ? "pass"
                                                                    : "no worse than before")
                                                            + style(
                                                                    "faint",
                                                                    " ("
                                                                            + run.command()
                                                                            + ", "
                                                                            + duration(
                                                                                    run.duration())
                                                                            + ")"))
                                    .orElse(style("fg(214)", ", tests not run")));
        }
        for (CleanupResult.Failed failed : result.failed()) {
            lines.add(
                    "  "
                            + style("fg(203)", "✗")
                            + " "
                            + failed.patch().edit().describe()
                            + style(
                                    "faint",
                                    ": dropped, "
                                            + failed.reason().lines().findFirst().orElse("")));
        }
        lines.add(
                "  Known vulnerabilities: "
                        + result.vulnerabilitiesBefore()
                        + " → "
                        + style("bold", String.valueOf(result.vulnerabilitiesAfter())));
        result.branch()
                .ifPresent(
                        branch -> {
                            String base =
                                    result.base()
                                            .substring(0, Math.min(10, result.base().length()));
                            lines.add("");
                            lines.add(
                                    "  "
                                            + style("faint", "Review: ")
                                            + "git log "
                                            + base
                                            + ".."
                                            + branch
                                            + style("faint", " · ")
                                            + "git diff "
                                            + base
                                            + " "
                                            + branch);
                        });
        lines.add("");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    String verification(Path root, Verification verification) {
        List<String> lines = header(root);
        lines.add(
                "  Compared with "
                        + style("bold", verification.base())
                        + ": "
                        + verification.findingsBefore()
                        + " → "
                        + verification.findingsAfter()
                        + " findings, security "
                        + verification.scoreBefore().security().score()
                        + " → "
                        + verification.scoreAfter().security().score()
                        + ", tech "
                        + verification.scoreBefore().tech().score()
                        + " → "
                        + verification.scoreAfter().tech().score());
        lines.add("");
        if (!verification.resolved().isEmpty()) {
            lines.add(
                    "  "
                            + style(
                                    "bold,fg(114)",
                                    "Resolved (" + verification.resolved().size() + ")"));
            verification.resolved().stream()
                    .limit(20)
                    .forEach(f -> lines.add("      " + where(f) + "  " + f.message()));
            lines.add("");
        }
        if (!verification.introduced().isEmpty()) {
            lines.add(
                    "  "
                            + style(
                                    "bold,fg(203)",
                                    "Introduced (" + verification.introduced().size() + ")"));
            verification
                    .introduced()
                    .forEach(f -> lines.add("      " + where(f) + "  " + f.message()));
            lines.add("");
        }
        verification
                .tests()
                .ifPresent(
                        run -> {
                            lines.add(
                                    "  Tests: "
                                            + (run.passed()
                                                    ? style("fg(114)", "pass")
                                                    : style("fg(203)", "fail"))
                                            + style("faint", " (" + run.command() + ")"));
                            if (!run.failed().isEmpty()) {
                                run.failed().forEach(t -> lines.add("      " + t));
                            }
                            lines.add("");
                        });
        lines.add(
                "  "
                        + (verification.green()
                                ? style("fg(114)", "✓ Verified: nothing new found")
                                : style(
                                        "fg(203)",
                                        "✗ Not yet: fix what's introduced"
                                                + (verification
                                                                .tests()
                                                                .map(t -> !t.passed())
                                                                .orElse(false)
                                                        ? " and the failing tests"
                                                        : ""))));
        lines.add("");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private String patchLine(String number, Patch patch) {
        String level =
                switch (patch.level()) {
                    case PATCH -> style("faint", "patch");
                    case MINOR -> style("fg(214)", "minor");
                    case MAJOR -> style("fg(203)", "major");
                };
        return "  "
                + style("bold", number)
                + "  "
                + patch.edit().describe()
                + style("faint", "  clears " + patch.proof().cleared().size() + " · ")
                + level;
    }

    private static List<String> detail(Patch patch) {
        List<String> details = new ArrayList<>();
        patch.bom().ifPresent(b -> details.add("moves all of " + b + " together"));
        if (!patch.alsoMoves().isEmpty()) {
            details.add("also moves " + String.join(", ", shortNames(patch.alsoMoves())));
        }
        if (patch.level() == Versions.Update.MINOR) {
            details.add("a minor-version jump: the tests decide whether it's safe");
        }
        return details;
    }

    private static List<String> shortNames(List<String> dependencies) {
        List<String> names =
                dependencies.stream().map(d -> d.substring(d.indexOf(':') + 1)).toList();
        return names.size() <= 4
                ? names
                : List.of(
                        String.join(", ", names.subList(0, 3))
                                + " and "
                                + (names.size() - 3)
                                + " more");
    }

    private static List<String> remainingByDependency(List<Finding> remaining) {
        Map<String, List<Finding>> byDependency = new LinkedHashMap<>();
        for (Finding finding : remaining) {
            String dependency =
                    finding.component()
                            .orElse("?")
                            .replaceFirst("^pkg:maven/", "")
                            .replaceFirst("/", ":")
                            .replace('@', ' ');
            byDependency.computeIfAbsent(dependency, d -> new ArrayList<>()).add(finding);
        }
        List<String> lines = new ArrayList<>();
        byDependency.forEach(
                (dependency, findings) -> {
                    long unfixed =
                            findings.stream()
                                    .filter(
                                            f ->
                                                    f.remediation()
                                                            .flatMap(Remediation::fixedVersion)
                                                            .isEmpty())
                                    .count();
                    String fix =
                            unfixed == findings.size()
                                    ? "no fixed version published for this line"
                                    : "fixed only in "
                                            + findings.stream()
                                                    .map(
                                                            f ->
                                                                    f.remediation()
                                                                            .flatMap(
                                                                                    Remediation
                                                                                            ::fixedVersion))
                                                    .flatMap(java.util.Optional::stream)
                                                    .distinct()
                                                    .sorted()
                                                    .reduce((a, b) -> b)
                                                    .orElse("?");
                    lines.add(
                            dependency
                                    + "  "
                                    + findings.size()
                                    + plural(findings.size(), " advisory", " advisories")
                                    + ", "
                                    + fix);
                });
        return lines;
    }

    private List<String> header(Path root) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(style("bold", "stealth clean") + "  " + root);
        lines.add("");
        return lines;
    }

    private static String where(Finding finding) {
        return finding.location()
                .path()
                .map(
                        p ->
                                p
                                        + finding.location().line().stream()
                                                .mapToObj(l -> ":" + l)
                                                .findFirst()
                                                .orElse(""))
                .orElse("(repository)");
    }

    private static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds < 60 ? seconds + " s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static String plural(long n, String one, String many) {
        return n == 1 ? one : many;
    }

    private String style(String styles, String text) {
        return ansi.string("@|" + styles + " " + text.replace("|@", "| @") + "|@");
    }
}
