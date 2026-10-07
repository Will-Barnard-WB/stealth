package dev.stealth.mcp;

import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.ConfigException;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.Severity;
import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.score.FixPlanner;
import dev.stealth.core.score.HealthScore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools agents call. Results are compact JSON (agents pay per token), with a {@code next} hint
 * where a follow-up call is likely. Every tool only reads.
 */
@Component
class StealthTools {

    static final int TOP_FIXES = 5;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(StealthTools.class);

    private final RepoScans scans;
    private final DependencyCheck dependencies;

    StealthTools(RepoScans scans, DependencyCheck dependencies) {
        this.scans = scans;
        this.dependencies = dependencies;
    }

    @McpTool(
            name = "repo_health",
            title = "Repository health",
            description =
                    """
                    Health check of a Java/Maven repository: security and tech-debt scores \
                    (0-100), finding counts by severity, the most important fixes, and which \
                    analyzers ran. Covers known vulnerabilities (OSV.dev), outdated and \
                    unmaintained dependencies, end-of-life Java and Spring Boot, leaked \
                    secrets, duplicated code and repo hygiene. Call it when the user asks how \
                    healthy, secure or up to date the codebase is, or before planning upgrade \
                    work. The first scan of a repository takes 10-60 seconds; later calls reuse \
                    it until files change. Then call list_findings for details.\
                    """,
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = true))
    CallToolResult repoHealth(
            @McpToolParam(
                            description =
                                    "Absolute path of the repository root, usually the current"
                                            + " working directory.")
                    String path,
            @McpToolParam(
                            description =
                                    "Scan again even if no file changed since the last scan."
                                            + " Default false.",
                            required = false)
                    Boolean refresh) {
        return call(
                "repo_health " + path,
                () -> {
                    RepoScans.Scan scan = scans.scan(directory(path), Boolean.TRUE.equals(refresh));
                    return health(scan);
                });
    }

    @McpTool(
            name = "list_findings",
            title = "List findings",
            description =
                    """
                    Findings for a Java/Maven repository, most severe first, each with where it \
                    is and how to fix it (such as the version that fixes a vulnerability). Filter \
                    by category (security for vulnerabilities, secrets and end-of-life; tech for \
                    outdated or unmaintained dependencies, duplication and hygiene), by minimum \
                    severity, or by analyzer. Call it when the user asks about specific \
                    problems, e.g. "what vulnerabilities do we have" (category=security). \
                    Reuses the last scan when nothing changed.\
                    """,
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = true))
    CallToolResult listFindings(
            @McpToolParam(
                            description =
                                    "Absolute path of the repository root, usually the current"
                                            + " working directory.")
                    String path,
            @McpToolParam(description = "security or tech. Default: both.", required = false)
                    String category,
            @McpToolParam(
                            description =
                                    "Minimum severity: critical, high, medium, low or info."
                                            + " Default: all.",
                            required = false)
                    String severity,
            @McpToolParam(
                            description =
                                    "Only this analyzer's findings: deps, vuln, maintenance, eol,"
                                            + " secrets, duplication or hygiene.",
                            required = false)
                    String analyzer,
            @McpToolParam(
                            description =
                                    "Maximum findings to return, 1-"
                                            + MAX_LIMIT
                                            + ". Default "
                                            + DEFAULT_LIMIT
                                            + ".",
                            required = false)
                    Integer limit,
            @McpToolParam(
                            description =
                                    "Scan again even if no file changed since the last scan."
                                            + " Default false.",
                            required = false)
                    Boolean refresh) {
        return call(
                "list_findings " + path,
                () -> {
                    Predicate<Finding> filter = filter(category, severity, analyzer);
                    int max = limit(limit);
                    RepoScans.Scan scan = scans.scan(directory(path), Boolean.TRUE.equals(refresh));
                    return findings(scan, filter, max);
                });
    }

    @McpTool(
            name = "check_dependency",
            title = "Check a Maven dependency",
            description =
                    """
                    Before adding or upgrading a Maven dependency, check it: the latest stable \
                    version, whether it's still maintained (a release in the last 2 years), and \
                    known vulnerabilities in the version you plan to use, with the version that \
                    fixes each. Needs no repository. Call it before adding a dependency to a \
                    pom.xml, or when the user asks whether a library or version is safe.\
                    """,
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = true))
    CallToolResult checkDependency(
            @McpToolParam(description = "Group id, e.g. org.apache.commons") String groupId,
            @McpToolParam(description = "Artifact id, e.g. commons-text") String artifactId,
            @McpToolParam(
                            description = "Version to check, e.g. 1.9. Default: the latest.",
                            required = false)
                    String version) {
        return call(
                "check_dependency " + groupId + ":" + artifactId,
                () ->
                        dependency(
                                dependencies.check(
                                        groupId,
                                        artifactId,
                                        Optional.ofNullable(version).filter(v -> !v.isBlank()))));
    }

    // --- repo_health

    static ObjectNode health(RepoScans.Scan scan) {
        DoctorReport report = scan.report();
        HealthScore score = report.score();
        ObjectNode json = JSON.createObjectNode();

        ObjectNode scores = json.putObject("score");
        if (score.overall().isPresent()) {
            scores.put("overall", score.overall().getAsInt());
            scores.put("grade", HealthScore.grade(score.overall().getAsInt()));
        } else {
            scores.putNull("overall");
        }
        scores.set("security", category(score.security()));
        scores.set("tech", category(score.tech()));
        if (score.overallCapped()) {
            scores.put("note", "A critical finding caps the overall score at 50.");
        }

        ObjectNode counts = json.putObject("findings");
        counts.put("total", report.findings().size());
        for (Category category : Category.values()) {
            Map<Severity, Integer> bySeverity = new EnumMap<>(Severity.class);
            report.findings().stream()
                    .filter(f -> f.category() == category)
                    .forEach(f -> bySeverity.merge(f.severity(), 1, Integer::sum));
            ObjectNode node = counts.putObject(lower(category));
            bySeverity.forEach((severity, n) -> node.put(lower(severity), n));
        }

        ArrayNode fixes = json.putArray("topFixes");
        for (FixPlanner.Fix fix : report.fixes().stream().limit(TOP_FIXES).toList()) {
            ObjectNode node = fixes.addObject();
            node.put("where", where(fix.location()));
            node.put("what", summary(fix));
            node.put("severity", lower(worst(fix.findings())));
            node.put("findings", fix.findings().size());
            node.put("pointsGained", Math.round(fix.points() * 10) / 10.0);
        }

        ObjectNode analyzers = json.putObject("analyzers");
        for (AnalyzerResult result : report.results()) {
            analyzers.put(
                    result.analyzerId(),
                    lower(result.status()) + result.error().map(e -> ": " + e).orElse(""));
        }
        warnings(json, report);
        scanned(json, scan);
        boolean vulnerable =
                report.findings().stream()
                        .anyMatch(f -> f.ruleId().equals("vuln/known-vulnerability"));
        json.put(
                "next",
                report.findings().isEmpty()
                        ? "Nothing to fix."
                        : (vulnerable
                                        ? "To fix the vulnerabilities, call plan_cleanup: it works"
                                              + " out proven version changes (don't edit pom.xml by"
                                              + " hand). "
                                        : "")
                                + "Call list_findings (e.g. category=security) for every finding"
                                + " with its fix.");
        return json;
    }

    private static ObjectNode category(HealthScore.CategoryScore category) {
        ObjectNode node = JSON.createObjectNode();
        if (category.status() == HealthScore.Status.NOT_RUN) {
            node.putNull("score");
        } else {
            node.put("score", category.score());
            node.put("grade", HealthScore.grade(category.score()));
        }
        node.put("status", lower(category.status()));
        return node;
    }

    /**
     * One line saying what to change, as the terminal report does: upgrade the dependency declared
     * at this line (to its latest version when it's outdated, else to the version fixing every
     * vulnerability), and which libraries' vulnerabilities that clears.
     */
    static String summary(FixPlanner.Fix fix) {
        List<Finding> findings = fix.findings();
        List<Finding> vulnerabilities =
                findings.stream().filter(f -> f.advisory().isPresent()).toList();
        Optional<Finding> outdated =
                findings.stream().filter(f -> f.ruleId().startsWith("deps/outdated-")).findFirst();
        boolean oneLibrary =
                !vulnerabilities.isEmpty()
                        && vulnerabilities.stream().map(Finding::component).distinct().count() == 1;
        Optional<String> component =
                outdated.or(
                                () ->
                                        oneLibrary
                                                ? Optional.of(vulnerabilities.getFirst())
                                                : Optional.empty())
                        .flatMap(Finding::component);
        if (component.isEmpty()) {
            String first = findings.getFirst().message();
            return findings.size() == 1
                    ? first
                    : first + " (and " + (findings.size() - 1) + " more here)";
        }
        Optional<String> target =
                outdated.isPresent()
                        ? outdated.flatMap(Finding::remediation).flatMap(Remediation::fixedVersion)
                        : vulnerabilities.stream()
                                .map(Finding::remediation)
                                .flatMap(Optional::stream)
                                .map(Remediation::fixedVersion)
                                .flatMap(Optional::stream)
                                .max(Comparator.comparing(ComparableVersion::new));
        StringBuilder summary =
                new StringBuilder("Upgrade ")
                        .append(coordinates(component.get()))
                        .append(" from ")
                        .append(version(component.get()));
        summary.append(target.map(t -> " to " + t).orElse(" (no fixed version published yet)"));
        if (!vulnerabilities.isEmpty()) {
            List<String> libraries =
                    vulnerabilities.stream()
                            .map(f -> f.component().map(StealthTools::artifactId).orElse("?"))
                            .distinct()
                            .toList();
            summary.append(": ")
                    .append(vulnerabilities.size())
                    .append(
                            plural(
                                    vulnerabilities.size(),
                                    " known vulnerability",
                                    " known vulnerabilities"))
                    .append(" in ")
                    .append(String.join(", ", libraries.stream().limit(3).toList()))
                    .append(libraries.size() > 3 ? " and " + (libraries.size() - 3) + " more" : "");
            String upgraded = coordinates(component.get());
            if (vulnerabilities.stream()
                    .anyMatch(
                            f ->
                                    !f.component()
                                            .map(StealthTools::coordinates)
                                            .orElse("")
                                            .equals(upgraded))) {
                // They come in through this dependency, so the upgrade fixes them only if its
                // new version brings fixed versions of those libraries
                summary.append(" (check they're fixed after upgrading)");
            }
        }
        return summary.toString();
    }

    /** {@code pkg:maven/org.example/lib@1.0} → {@code org.example:lib}. */
    private static String coordinates(String purl) {
        return purl.replaceFirst("^pkg:maven/", "").replaceFirst("@.*$", "").replace('/', ':');
    }

    private static String artifactId(String purl) {
        String coordinates = coordinates(purl);
        return coordinates.substring(coordinates.indexOf(':') + 1);
    }

    private static String version(String purl) {
        int at = purl.lastIndexOf('@');
        return at < 0 ? "?" : purl.substring(at + 1);
    }

    // --- list_findings

    static ObjectNode findings(RepoScans.Scan scan, Predicate<Finding> filter, int limit) {
        List<Finding> matching =
                scan.report().findings().stream()
                        .filter(filter)
                        .sorted(
                                Comparator.comparing(Finding::severity)
                                        .thenComparing(f -> f.location().path().orElse(""))
                                        .thenComparingInt(f -> f.location().line().orElse(0)))
                        .toList();
        ObjectNode json = JSON.createObjectNode();
        json.put("total", matching.size());
        ArrayNode items = json.putArray("findings");
        for (Finding finding : matching.stream().limit(limit).toList()) {
            ObjectNode node = items.addObject();
            node.put("rule", finding.ruleId());
            node.put("severity", lower(finding.severity()));
            node.put("message", finding.message());
            node.put("where", where(finding.location()));
            finding.component().ifPresent(c -> node.put("component", c));
            finding.advisory()
                    .ifPresent(
                            a -> {
                                node.put("advisory", a.id());
                                if (!a.aliases().isEmpty()) {
                                    node.put("aliases", String.join(", ", a.aliases()));
                                }
                            });
            finding.remediation()
                    .ifPresent(
                            r -> {
                                r.fixedVersion().ifPresent(v -> node.put("fixedVersion", v));
                                r.description().ifPresent(d -> node.put("fix", d));
                            });
        }
        if (matching.stream().anyMatch(f -> f.ruleId().equals("vuln/known-vulnerability"))) {
            json.put(
                    "fixVulnerabilities",
                    "Call plan_cleanup for proven version changes that fix these, instead of"
                            + " editing pom.xml by hand.");
        }
        if (matching.size() > limit) {
            json.put(
                    "next",
                    "Showing "
                            + limit
                            + " of "
                            + matching.size()
                            + ". Narrow with severity, category or analyzer, or raise limit.");
        }
        warnings(json, scan.report());
        scanned(json, scan);
        return json;
    }

    static Predicate<Finding> filter(String category, String severity, String analyzer) {
        Predicate<Finding> filter = f -> true;
        if (category != null && !category.isBlank()) {
            Category wanted = parse(Category.class, category, "category must be security or tech");
            filter = filter.and(f -> f.category() == wanted);
        }
        if (severity != null && !severity.isBlank()) {
            Severity minimum =
                    parse(
                            Severity.class,
                            severity,
                            "severity must be critical, high, medium, low or info");
            // Severity is declared most severe first
            filter = filter.and(f -> f.severity().compareTo(minimum) <= 0);
        }
        if (analyzer != null && !analyzer.isBlank()) {
            String prefix = analyzer.strip().toLowerCase(Locale.ROOT) + "/";
            filter = filter.and(f -> f.ruleId().startsWith(prefix));
        }
        return filter;
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
        }
        return limit;
    }

    // --- check_dependency

    static ObjectNode dependency(DependencyCheck.Result result) {
        ObjectNode json = JSON.createObjectNode();
        String coordinates = result.groupId() + ":" + result.artifactId();
        json.put("dependency", coordinates);
        json.put("onMavenCentral", result.found());
        result.version().ifPresent(v -> json.put("version", v));
        result.latestVersion().ifPresent(v -> json.put("latestVersion", v));
        result.latestReleased().ifPresent(d -> json.put("latestReleased", d.toString()));
        result.maintained().ifPresent(m -> json.put("maintained", m));
        ArrayNode vulnerabilities = json.putArray("vulnerabilities");
        for (Finding finding : result.vulnerabilities()) {
            ObjectNode node = vulnerabilities.addObject();
            finding.advisory()
                    .ifPresent(
                            a -> {
                                node.put("id", a.id());
                                if (!a.aliases().isEmpty()) {
                                    node.put("aliases", String.join(", ", a.aliases()));
                                }
                                a.cvssScore().ifPresent(s -> node.put("cvss", s));
                            });
            node.put("severity", lower(finding.severity()));
            node.put("message", finding.message());
            finding.remediation()
                    .flatMap(Remediation::fixedVersion)
                    .ifPresent(v -> node.put("fixedVersion", v));
        }
        result.latestVulnerabilities().ifPresent(n -> json.put("latestVersionVulnerabilities", n));
        json.put("verdict", verdict(result));
        return json;
    }

    static String verdict(DependencyCheck.Result result) {
        String coordinates = result.groupId() + ":" + result.artifactId();
        if (!result.found()) {
            return coordinates
                    + " isn't on Maven Central. Check the coordinates; if it comes from another"
                    + " repository, stealth can't check it.";
        }
        StringBuilder verdict = new StringBuilder();
        String version = result.version().orElse("?");
        List<Finding> found = result.vulnerabilities();
        if (found.isEmpty()) {
            verdict.append(version).append(" has no known vulnerabilities.");
        } else {
            Optional<String> fixedIn =
                    found.stream()
                            .map(Finding::remediation)
                            .flatMap(Optional::stream)
                            .map(Remediation::fixedVersion)
                            .flatMap(Optional::stream)
                            .max(Comparator.comparing(ComparableVersion::new));
            verdict.append("Avoid ")
                    .append(version)
                    .append(": ")
                    .append(found.size())
                    .append(plural(found.size(), " known vulnerability", " known vulnerabilities"))
                    .append(", worst ")
                    .append(lower(worst(found)))
                    .append(fixedIn.map(v -> ", all fixed by " + v).orElse(""))
                    .append('.');
        }
        if (result.latestVersion().isPresent()
                && !result.latestVersion().equals(result.version())) {
            verdict.append(" The latest stable version is ").append(result.latestVersion().get());
            result.latestVulnerabilities()
                    .ifPresent(
                            n ->
                                    verdict.append(
                                            n == 0
                                                    ? ", with no known vulnerabilities"
                                                    : ", which has "
                                                            + n
                                                            + plural(
                                                                    n,
                                                                    " known vulnerability",
                                                                    " known vulnerabilities")
                                                            + " itself"));
            verdict.append('.');
        }
        if (result.maintained().isPresent() && !result.maintained().get()) {
            verdict.append(" It looks unmaintained: no release since ")
                    .append(result.latestReleased().map(Object::toString).orElse("?"))
                    .append(". Consider a maintained alternative.");
        }
        return verdict.toString();
    }

    // --- shared

    /** Runs a tool, turning bad input and unreachable services into an error the agent can read. */
    private CallToolResult call(String description, ToolBody body) {
        Instant started = Instant.now();
        try {
            ObjectNode json = body.run();
            LOG.info(
                    "{} in {} ms",
                    description,
                    Duration.between(started, Instant.now()).toMillis());
            return CallToolResult.builder().addTextContent(JSON.writeValueAsString(json)).build();
        } catch (IllegalArgumentException | ConfigException | IOException e) {
            LOG.info("{} failed: {}", description, e.getMessage());
            return error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Interrupted");
        } catch (RuntimeException e) {
            LOG.warn("{} failed", description, e);
            return error("stealth failed: " + e);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    @FunctionalInterface
    private interface ToolBody {
        ObjectNode run() throws IOException, ConfigException, InterruptedException;
    }

    /** The repository root, which must be an existing directory given as an absolute path. */
    static Path directory(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required: the repository's absolute path");
        }
        Path directory = Path.of(path.strip());
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException(
                    "path must be absolute (the repository root, usually the current working"
                            + " directory), got '"
                            + path
                            + "'");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("not a directory: " + path);
        }
        return directory.normalize();
    }

    private static void warnings(ObjectNode json, DoctorReport report) {
        if (!report.warnings().isEmpty()) {
            ArrayNode warnings = json.putArray("warnings");
            report.warnings().forEach(warnings::add);
        }
        List<String> failed =
                report.results().stream()
                        .filter(
                                r ->
                                        r.status() == AnalyzerStatus.FAILED
                                                || r.status() == AnalyzerStatus.TIMED_OUT)
                        .map(r -> r.analyzerId() + " " + lower(r.status()))
                        .toList();
        if (!failed.isEmpty()) {
            json.put(
                    "incomplete",
                    String.join(", ", failed) + ": their findings are missing from this result.");
        }
    }

    private static void scanned(ObjectNode json, RepoScans.Scan scan) {
        json.put("scannedAt", scan.scannedAt().toString());
        json.put("fromCache", scan.cached());
    }

    static String where(Location location) {
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

    private static Severity worst(List<Finding> findings) {
        return findings.stream()
                .map(Finding::severity)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message + ", got '" + value + "'");
        }
    }

    private static String plural(long n, String one, String many) {
        return n == 1 ? one : many;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
