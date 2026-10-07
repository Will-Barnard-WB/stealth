package dev.stealth.core.clean;

import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.score.HealthScore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code stealth clean}'s results as compact JSON, for {@code --json} and the MCP tools: the same
 * shape for scripts and agents. Each ends with {@code next}: what to do with it.
 */
public final class CleanupJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CleanupJson() {}

    public static String plan(CleanupPlan plan) {
        ObjectNode json = JSON.createObjectNode();
        json.put("vulnerabilities", plan.vulnerabilities());
        List<Patch> safe = plan.safePatches();
        plan.combined()
                .ifPresent(
                        combined -> {
                            json.put("clearedBySafePatches", combined.cleared().size());
                            json.put("safePatchesTogether", lower(combined.status()));
                        });
        ArrayNode patches = json.putArray("patches");
        for (Patch patch : plan.patches()) {
            patches.add(patch(patch));
        }

        // What no safe patch clears, by dependency
        Map<String, ObjectNode> remaining = new LinkedHashMap<>();
        for (Finding finding : plan.remaining()) {
            String dependency =
                    finding.component()
                            .orElse("?")
                            .replaceFirst("^pkg:maven/", "")
                            .replaceFirst("/", ":");
            ObjectNode node =
                    remaining.computeIfAbsent(
                            dependency,
                            d -> {
                                ObjectNode created = JSON.createObjectNode();
                                created.put("dependency", d);
                                created.putArray("advisories");
                                return created;
                            });
            ObjectNode advisory = ((ArrayNode) node.get("advisories")).addObject();
            finding.advisory().ifPresent(a -> advisory.put("id", a.id()));
            advisory.put("severity", lower(finding.severity()));
            advisory.put(
                    "fixedIn",
                    finding.remediation()
                            .flatMap(Remediation::fixedVersion)
                            .orElse("no fixed version published"));
        }
        ArrayNode migration = json.putArray("needsMigration");
        remaining.values().forEach(migration::add);

        ArrayNode secrets = json.putArray("secrets");
        for (Finding secret : plan.secrets()) {
            secrets.addObject()
                    .put("where", where(secret.location()))
                    .put("rule", secret.ruleId())
                    .put("message", secret.message());
        }

        json.put(
                "next",
                safe.isEmpty()
                        ? "No safe patches. Remaining vulnerabilities need a major upgrade or have"
                              + " no fix yet; secrets need the user to rotate them and you to move"
                              + " them out of code."
                        : "Show the user this plan. If they agree, call apply_cleanup to put the "
                                + safe.size()
                                + " safe patches on a new branch (tests are run before and after)."
                                + (plan.reviewPatches().isEmpty()
                                        ? ""
                                        : " "
                                                + plan.reviewPatches().size()
                                                + " more are proven but jump past the versions the"
                                                + " framework manages (allowMinor): explain the"
                                                + " risk and let the user decide.")
                                + (plan.secrets().isEmpty()
                                        ? ""
                                        : " Secrets: ask the user to rotate them, then move them"
                                                + " out of code and call verify_cleanup."));
        return JSON.writeValueAsString(json);
    }

    private static ObjectNode patch(Patch patch) {
        ObjectNode node = JSON.createObjectNode();
        node.put("change", patch.edit().describe());
        node.put("pom", patch.edit().pomPath());
        node.put("safe", patch.safe());
        node.put("needsReview", patch.needsReview());
        node.put("level", lower(patch.level()));
        node.put("proof", lower(patch.proof().status()));
        node.put("clears", patch.proof().cleared().size());
        node.put("targets", patch.targets().size());
        patch.bom().ifPresent(b -> node.put("movesBom", b));
        if (!patch.alsoMoves().isEmpty()) {
            node.put("alsoMoves", String.join(", ", patch.alsoMoves()));
        }
        ArrayNode changes = node.putArray("dependencies");
        for (Patch.VersionChange change : patch.changes()) {
            changes.addObject()
                    .put("dependency", change.dependency())
                    .put("from", change.from())
                    .put("to", change.to());
        }
        if (!patch.proof().introduced().isEmpty()) {
            node.put("introduces", String.join(", ", patch.proof().introduced()));
        }
        patch.proof().problem().ifPresent(p -> node.put("problem", p));
        if (patch.crossesMajor()) {
            node.put("note", "Moves to a new major version: not applied unless allowMajor is set.");
        } else if (patch.needsReview()) {
            node.put(
                    "note",
                    "A minor jump past the version the parent or BOM manages, which the framework"
                            + " wasn't tested with: not applied unless allowMinor is set. Check the"
                            + " library's compatibility with the framework first.");
        }
        return node;
    }

    public static String result(CleanupResult result) {
        ObjectNode json = JSON.createObjectNode();
        result.branch().ifPresentOrElse(b -> json.put("branch", b), () -> json.putNull("branch"));
        json.put("base", result.base());
        ObjectNode vulnerabilities = json.putObject("vulnerabilities");
        vulnerabilities.put("before", result.vulnerabilitiesBefore());
        vulnerabilities.put("after", result.vulnerabilitiesAfter());
        ArrayNode applied = json.putArray("applied");
        for (CleanupResult.Applied a : result.applied()) {
            applied.addObject()
                    .put("change", a.patch().edit().describe())
                    .put("commit", a.commit())
                    .put("clears", a.patch().proof().cleared().size());
        }
        ArrayNode failed = json.putArray("failed");
        for (CleanupResult.Failed f : result.failed()) {
            failed.addObject().put("change", f.patch().edit().describe()).put("reason", f.reason());
        }
        ObjectNode tests = json.putObject("tests");
        if (result.baseline().isEmpty()) {
            tests.put("ran", false);
        } else {
            tests.put("ran", true);
            tests.put("command", result.baseline().get().command());
            tests.put("baselinePassed", result.baseline().get().passed());
            result.after().ifPresent(after -> tests.put("afterPassed", after.passed()));
        }
        json.put(
                "next",
                result.branch()
                        .map(
                                b ->
                                        "Review the branch with the user: git log "
                                                + result.base()
                                                        .substring(
                                                                0,
                                                                Math.min(
                                                                        12, result.base().length()))
                                                + ".."
                                                + b
                                                + " and git diff "
                                                + result.base()
                                                        .substring(
                                                                0,
                                                                Math.min(
                                                                        12, result.base().length()))
                                                + " "
                                                + b
                                                + ". Each commit is one patch. Their checkout is"
                                                + " unchanged; they can merge or switch to it.")
                        .orElse("Nothing was applied."));
        return JSON.writeValueAsString(json);
    }

    public static String verification(Verification verification) {
        ObjectNode json = JSON.createObjectNode();
        json.put("base", verification.base());
        json.put("green", verification.green());
        ObjectNode score = json.putObject("score");
        score.set("before", score(verification.scoreBefore()));
        score.set("after", score(verification.scoreAfter()));
        json.put("findingsBefore", verification.findingsBefore());
        json.put("findingsAfter", verification.findingsAfter());
        json.set("resolved", findings(verification.resolved()));
        json.set("introduced", findings(verification.introduced()));
        verification
                .tests()
                .ifPresent(
                        run -> {
                            ObjectNode tests = json.putObject("tests");
                            tests.put("passed", run.passed());
                            tests.put("command", run.command());
                            if (!run.failed().isEmpty()) {
                                tests.put("failing", String.join(", ", run.failed()));
                            }
                            if (!run.passed()) {
                                tests.put("output", run.output());
                            }
                        });
        json.put(
                "next",
                verification.green()
                        ? "Verified: nothing new found"
                                + (verification.tests().isPresent() ? " and the tests pass." : ".")
                        : "Fix what's listed under introduced"
                                + (verification.tests().map(t -> !t.passed()).orElse(false)
                                        ? " and the failing tests"
                                        : "")
                                + ", then call verify_cleanup again.");
        return JSON.writeValueAsString(json);
    }

    public static String impact(dev.stealth.core.impact.UpgradeImpact.Result impact) {
        ObjectNode json = JSON.createObjectNode();
        json.put("dependency", impact.dependency());
        json.put("from", impact.from());
        json.put("to", impact.to());
        json.put("precise", impact.precise());
        json.put("apisRemoved", impact.removed());
        json.put("apisDeprecated", impact.deprecated());
        json.put("breakingUsages", impact.breaking());
        ArrayNode usages = json.putArray("usages");
        for (dev.stealth.core.impact.UpgradeImpact.Usage usage :
                impact.usages().stream().limit(200).toList()) {
            ObjectNode node = usages.addObject();
            node.put("where", usage.where());
            node.put("api", usage.api());
            node.put("change", lower(usage.kind()));
            usage.hint().ifPresent(h -> node.put("hint", h));
        }
        if (impact.usages().size() > 200) {
            json.put("truncated", impact.usages().size() - 200);
        }
        ArrayNode notes = json.putArray("notes");
        impact.notes().forEach(notes::add);
        json.put(
                "next",
                impact.usages().isEmpty()
                        ? "Nothing in this repository uses an API the upgrade removes or"
                              + " deprecates; the version change itself is the work (and the tests"
                              + " decide)."
                        : "Change each removed usage (hints say what to use instead), then"
                                + " deprecated ones if convenient; change the version, and call"
                                + " verify_cleanup with runTests.");
        return JSON.writeValueAsString(json);
    }

    private static ObjectNode score(HealthScore score) {
        ObjectNode node = JSON.createObjectNode();
        if (score.overall().isPresent()) {
            node.put("overall", score.overall().getAsInt());
        } else {
            node.putNull("overall");
        }
        node.put("security", score.security().score());
        node.put("tech", score.tech().score());
        return node;
    }

    private static ArrayNode findings(List<Finding> findings) {
        ArrayNode array = JSON.createArrayNode();
        for (Finding finding : findings.stream().limit(50).toList()) {
            array.addObject()
                    .put("rule", finding.ruleId())
                    .put("severity", lower(finding.severity()))
                    .put("where", where(finding.location()))
                    .put("message", finding.message());
        }
        return array;
    }

    private static String where(Location location) {
        return location.path()
                .map(
                        p ->
                                p
                                        + location.line().stream()
                                                .mapToObj(l -> ":" + l)
                                                .findFirst()
                                                .orElse(""))
                .orElse("(whole repository)");
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
