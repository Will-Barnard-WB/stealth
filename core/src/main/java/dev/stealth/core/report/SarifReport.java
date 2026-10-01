package dev.stealth.core.report;

import static dev.stealth.core.report.ReportJson.lower;
import static dev.stealth.core.report.ReportJson.object;

import dev.stealth.core.Advisory;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import dev.stealth.core.score.HealthScore;
import dev.stealth.core.score.ScoringEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SARIF 2.1.0 for GitHub code scanning, mapped exactly as ADR-0003 says (see the sample in {@code
 * docs/adr/examples/doctor.sarif}). Rule ids and fingerprints are what GitHub keys alert history
 * on, so they must not change between runs or versions.
 */
public final class SarifReport {

    static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    static final String FINGERPRINT_KEY = "stealth/v1";
    private static final String INFORMATION_URI = "https://github.com/Will-Barnard-WB/stealth";

    private SarifReport() {}

    /**
     * @param rules every rule the analyzers can report, for descriptions and help links
     * @param buildFile the repository's root build file ({@code pom.xml}) if it has one, which
     *     repository-level findings are anchored to
     */
    public static String render(
            DoctorReport report, List<Rule> rules, String toolVersion, Optional<String> buildFile) {
        Map<String, Rule> byId =
                rules.stream()
                        .collect(Collectors.toMap(Rule::id, Function.identity(), (a, b) -> a));

        // One SARIF rule per rule with results; advisory findings get one per advisory (ADR-0003)
        Map<String, ObjectNode> sarifRules = new LinkedHashMap<>();
        Map<String, Integer> ruleIndex = new LinkedHashMap<>();
        List<ObjectNode> results = new ArrayList<>();
        for (Finding finding : report.findings()) {
            String sarifId = sarifRuleId(finding);
            if (!sarifRules.containsKey(sarifId)) {
                ruleIndex.put(sarifId, sarifRules.size());
                sarifRules.put(sarifId, rule(sarifId, finding, byId.get(finding.ruleId())));
            }
            results.add(result(finding, sarifId, ruleIndex.get(sarifId), buildFile));
        }

        ObjectNode sarif = object();
        sarif.put("$schema", SCHEMA);
        sarif.put("version", "2.1.0");
        ObjectNode run = sarif.putArray("runs").addObject();
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", "stealth");
        driver.put("semanticVersion", toolVersion);
        driver.put("informationUri", INFORMATION_URI);
        ArrayNode rulesNode = driver.putArray("rules");
        sarifRules.values().forEach(rulesNode::add);
        run.putObject("automationDetails").put("id", "stealth/doctor/");
        ArrayNode resultsNode = run.putArray("results");
        results.forEach(resultsNode::add);
        run.putObject("properties").set("stealthScore", score(report.score()));
        return ReportJson.write(sarif);
    }

    static String sarifRuleId(Finding finding) {
        return finding.advisory()
                .map(a -> finding.ruleId() + "/" + a.id())
                .orElse(finding.ruleId());
    }

    private static ObjectNode rule(String sarifId, Finding finding, Rule rule) {
        Optional<Advisory> advisory = finding.advisory();
        ObjectNode node = object();
        node.put("id", sarifId);
        node.put("name", pascalCase(finding.ruleId()));
        String fullDescription = rule != null ? rule.shortDescription() : finding.ruleId();
        node.putObject("shortDescription")
                .put(
                        "text",
                        advisory.isPresent()
                                ? ReportJson.advisorySummary(finding)
                                : rule != null ? rule.name() : finding.ruleId());
        node.putObject("fullDescription").put("text", fullDescription);
        Optional<String> help =
                advisory.flatMap(Advisory::url)
                        .map(Object::toString)
                        .or(() -> Optional.ofNullable(rule).map(r -> r.helpUri().toString()));
        help.ifPresent(uri -> node.put("helpUri", uri));
        Severity defaultSeverity = rule != null ? rule.defaultSeverity() : finding.severity();
        node.putObject("defaultConfiguration").put("level", level(defaultSeverity));

        ObjectNode properties = node.putObject("properties");
        ArrayNode tags = properties.putArray("tags");
        tags.add(finding.category() == Category.SECURITY ? "security" : "maintainability");
        if (finding.component().isPresent()) {
            tags.add("dependency");
        }
        if (finding.ruleId().startsWith("secrets/")) {
            tags.add("secret");
        }
        properties.put(
                "precision", finding.ruleId().equals("secrets/generic-secret") ? "medium" : "high");
        if (finding.category() == Category.SECURITY) {
            // GitHub reads security-severity from the rule: an advisory's own CVSS score when it
            // has one, otherwise the band for the severity
            double score =
                    advisory.flatMap(
                                    a ->
                                            a.cvssScore().isPresent()
                                                    ? Optional.of(a.cvssScore().getAsDouble())
                                                    : Optional.empty())
                            .orElseGet(
                                    () ->
                                            securitySeverity(
                                                    advisory.isPresent()
                                                            ? finding.severity()
                                                            : defaultSeverity));
            if (score > 0) {
                properties.put("security-severity", String.format(Locale.ROOT, "%.1f", score));
            }
        }
        properties.put("stealthRuleId", finding.ruleId());
        properties.put("stealthCategory", lower(finding.category()));
        return node;
    }

    private static ObjectNode result(
            Finding finding, String sarifId, int ruleIndex, Optional<String> buildFile) {
        ObjectNode result = object();
        result.put("ruleId", sarifId);
        result.put("ruleIndex", ruleIndex);
        result.put("level", level(finding.severity()));
        Location location = finding.location();
        boolean repositoryLevel = location.path().isEmpty();
        result.putObject("message")
                .put(
                        "text",
                        finding.message()
                                + (repositoryLevel ? " (applies to the whole repository)" : ""));

        ObjectNode sarifLocation = result.putArray("locations").addObject();
        Optional<String> path = repositoryLevel ? buildFile : location.path();
        path.ifPresent(
                p -> {
                    ObjectNode physical = sarifLocation.putObject("physicalLocation");
                    physical.putObject("artifactLocation")
                            .put("uri", p)
                            .put("uriBaseId", "%SRCROOT%");
                    physical.putObject("region")
                            .put("startLine", repositoryLevel ? 1 : location.line().orElse(1));
                });
        ObjectNode logical = sarifLocation.putArray("logicalLocations").addObject();
        if (repositoryLevel) {
            logical.put("name", ".").put("kind", "repository");
        } else {
            String module = location.module().filter(m -> !m.isEmpty()).orElse(".");
            logical.put("name", module).put("kind", "module");
        }

        if (!finding.relatedLocations().isEmpty()) {
            ArrayNode related = result.putArray("relatedLocations");
            for (int i = 0; i < finding.relatedLocations().size(); i++) {
                Location other = finding.relatedLocations().get(i);
                ObjectNode node = related.addObject();
                node.put("id", i + 1);
                ObjectNode physical = node.putObject("physicalLocation");
                physical.putObject("artifactLocation")
                        .put("uri", other.path().orElseThrow())
                        .put("uriBaseId", "%SRCROOT%");
                physical.putObject("region").put("startLine", other.line().orElse(1));
            }
        }
        result.putObject("partialFingerprints").put(FINGERPRINT_KEY, finding.fingerprint());
        ObjectNode properties = result.putObject("properties");
        properties.put("severity", lower(finding.severity()));
        finding.component().ifPresent(c -> properties.put("component", c));
        finding.advisory()
                .ifPresent(
                        a -> {
                            properties.put("advisory", a.id());
                            ArrayNode aliases = properties.putArray("aliases");
                            a.aliases().forEach(aliases::add);
                        });
        finding.remediation()
                .flatMap(Remediation::fixedVersion)
                .ifPresent(v -> properties.put("fixedVersion", v));
        return result;
    }

    private static ObjectNode score(HealthScore score) {
        ObjectNode node = object();
        if (score.overall().isPresent()) {
            node.put("overall", score.overall().getAsInt());
        } else {
            node.putNull("overall");
        }
        node.put("tech", score.tech().score());
        node.put("security", score.security().score());
        node.put("scoringVersion", ScoringEngine.SCORING_VERSION);
        return node;
    }

    /** ADR-0003: critical and high are errors, medium a warning, low and info notes. */
    static String level(Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    /** ADR-0003's bands, for security rules without a CVSS score. Info has none. */
    static double securitySeverity(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 9.5;
            case HIGH -> 8.0;
            case MEDIUM -> 5.5;
            case LOW -> 2.0;
            case INFO -> 0;
        };
    }

    /** {@code vuln/known-vulnerability} → {@code KnownVulnerability}. */
    static String pascalCase(String ruleId) {
        String last = ruleId.substring(ruleId.lastIndexOf('/') + 1);
        StringBuilder name = new StringBuilder();
        for (String part : last.split("-")) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }
}
