package dev.stealth.core.report;

import static dev.stealth.core.report.ReportJson.lower;
import static dev.stealth.core.report.ReportJson.object;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.score.FailUnderGate;
import dev.stealth.core.score.FixPlanner;
import dev.stealth.core.score.HealthScore;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code stealth doctor --json}: the whole report for scripts, agents and the platform. The shape
 * is {@code docs/schema/doctor-report.schema.json}; adding fields keeps {@link #SCHEMA_VERSION},
 * renaming or removing them bumps it.
 */
public final class JsonReport {

    public static final int SCHEMA_VERSION = 1;

    private JsonReport() {}

    /**
     * @param gate the {@code fail-under} verdict, when a threshold was set
     */
    public static String render(
            DoctorReport report, String toolVersion, Optional<FailUnderGate.Result> gate) {
        ObjectNode json = object();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.putObject("tool").put("name", "stealth").put("version", toolVersion);
        json.set("score", score(report.score()));
        gate.ifPresent(g -> json.set("failUnder", gate(g)));

        ArrayNode fixes = json.putArray("fixes");
        for (FixPlanner.Fix fix : report.fixes()) {
            ObjectNode node = fixes.addObject();
            node.set("location", location(fix.location()));
            node.put("points", round(fix.points()));
            ArrayNode fingerprints = node.putArray("findings");
            fix.findings().forEach(f -> fingerprints.add(f.fingerprint()));
        }

        ArrayNode findings = json.putArray("findings");
        report.findings().forEach(f -> findings.add(finding(f)));

        ArrayNode analyzers = json.putArray("analyzers");
        for (AnalyzerResult result : report.results()) {
            ObjectNode node = analyzers.addObject();
            node.put("id", result.analyzerId());
            node.put("category", lower(result.category()));
            node.put("status", lower(result.status()));
            node.put("durationMs", result.duration().toMillis());
            result.error().ifPresent(e -> node.put("error", e));
        }

        ArrayNode warnings = json.putArray("warnings");
        report.warnings().forEach(warnings::add);
        return ReportJson.write(json);
    }

    private static ObjectNode score(HealthScore score) {
        ObjectNode node = object();
        if (score.overall().isPresent()) {
            node.put("overall", score.overall().getAsInt());
            node.put("grade", HealthScore.grade(score.overall().getAsInt()));
        } else {
            node.putNull("overall");
            node.putNull("grade");
        }
        node.put("overallCapped", score.overallCapped());
        node.put("scoringVersion", score.scoringVersion());
        node.set("security", category(score.security()));
        node.set("tech", category(score.tech()));
        return node;
    }

    private static ObjectNode category(HealthScore.CategoryScore category) {
        ObjectNode node = object();
        node.put("status", lower(category.status()));
        if (category.status() == HealthScore.Status.NOT_RUN) {
            node.putNull("score");
            node.putNull("grade");
        } else {
            node.put("score", category.score());
            node.put("grade", HealthScore.grade(category.score()));
        }
        node.put("capped", category.capped());
        ArrayNode analyzers = node.putArray("analyzers");
        category.analyzers().forEach(analyzers::add);
        ArrayNode deductions = node.putArray("deductions");
        for (HealthScore.Deduction deduction : category.deductions()) {
            deductions
                    .addObject()
                    .put("analyzer", deduction.analyzerId())
                    .put("findings", deduction.findings())
                    .put("points", round(deduction.points()))
                    .put("capped", deduction.capped());
        }
        return node;
    }

    private static ObjectNode gate(FailUnderGate.Result result) {
        ObjectNode node = object();
        node.put("passed", result.passed());
        node.put("exitCode", result.exitCode());
        ArrayNode failures = node.putArray("failures");
        result.failures().forEach(failures::add);
        result.error().ifPresent(e -> node.put("error", e));
        return node;
    }

    private static ObjectNode finding(Finding finding) {
        ObjectNode node = object();
        node.put("ruleId", finding.ruleId());
        node.put("category", lower(finding.category()));
        node.put("severity", lower(finding.severity()));
        node.put("message", finding.message());
        node.set("location", location(finding.location()));
        if (!finding.relatedLocations().isEmpty()) {
            ArrayNode related = node.putArray("relatedLocations");
            finding.relatedLocations().forEach(l -> related.add(location(l)));
        }
        finding.component().ifPresent(c -> node.put("component", c));
        finding.advisory()
                .ifPresent(
                        a -> {
                            ObjectNode advisory = node.putObject("advisory");
                            advisory.put("id", a.id());
                            ArrayNode aliases = advisory.putArray("aliases");
                            a.aliases().forEach(aliases::add);
                            a.cvssScore().ifPresent(s -> advisory.put("cvssScore", s));
                            a.url().ifPresent(u -> advisory.put("url", u.toString()));
                        });
        finding.remediation()
                .ifPresent(
                        r -> {
                            ObjectNode remediation = node.putObject("remediation");
                            r.fixedVersion().ifPresent(v -> remediation.put("fixedVersion", v));
                            r.description().ifPresent(d -> remediation.put("description", d));
                        });
        node.put("fingerprint", finding.fingerprint());
        return node;
    }

    /** Repo-relative with forward slashes on every OS; a missing path means the whole repo. */
    private static ObjectNode location(Location location) {
        ObjectNode node = object();
        location.path().ifPresentOrElse(p -> node.put("path", p), () -> node.putNull("path"));
        location.line().ifPresent(l -> node.put("line", l));
        location.module().ifPresent(m -> node.put("module", m));
        return node;
    }

    private static double round(double points) {
        return Math.round(points * 100) / 100.0;
    }
}
