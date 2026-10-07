package dev.stealth.mcp;

import dev.stealth.core.ConfigException;
import dev.stealth.core.RepoContext;
import dev.stealth.core.clean.CleanException;
import dev.stealth.core.clean.Cleaner;
import dev.stealth.core.clean.CleanupJson;
import dev.stealth.core.clean.CleanupPlan;
import dev.stealth.core.clean.CleanupResult;
import dev.stealth.core.clean.CleanupVerifier;
import dev.stealth.core.clean.PatchPlanner;
import dev.stealth.core.clean.TestRunner;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * {@code stealth clean} for agents: what can be fixed now (proven), applying it on a new branch,
 * and verifying changes. The agent makes code changes; stealth only commits proven version patches
 * to a branch of its own.
 */
@Component
class CleanupTools {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupTools.class);

    private final RepoScans scans;
    private final PatchPlanner planner;
    private final Cleaner cleaner;
    private final CleanupVerifier verifier;

    CleanupTools(RepoScans scans, PatchPlanner planner, Cleaner cleaner, CleanupVerifier verifier) {
        this.scans = scans;
        this.planner = planner;
        this.cleaner = cleaner;
        this.verifier = verifier;
    }

    @McpTool(
            name = "plan_cleanup",
            title = "Plan a cleanup",
            description =
                    """
                    Fix known vulnerabilities in a Java/Maven repository: use this instead of \
                    editing pom.xml versions by hand. Works out what can be fixed now without a \
                    framework upgrade: version patches for known vulnerabilities (bumps, the properties a \
                    parent like Spring Boot exposes such as tomcat.version, or dependencyManagement \
                    pins), each proven by re-resolving the dependency tree and checking OSV again; \
                    patches that need a new major version; what no version change can fix; and \
                    leaked secrets. Call it after repo_health when the user wants to fix \
                    vulnerabilities. Show the user the plan before calling apply_cleanup. Changes \
                    nothing.\
                    """,
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = true))
    CallToolResult planCleanup(
            @McpToolParam(description = "Absolute path of the repository root.") String path,
            @McpToolParam(
                            description = "Scan again even if no file changed. Default false.",
                            required = false)
                    Boolean refresh) {
        return call(
                "plan_cleanup " + path,
                () -> {
                    Path root = StealthTools.directory(path);
                    RepoContext context = scans.context(root);
                    RepoScans.Scan scan = scans.scan(root, Boolean.TRUE.equals(refresh));
                    return CleanupJson.plan(planner.plan(context, scan.report()));
                });
    }

    @McpTool(
            name = "apply_cleanup",
            title = "Apply the cleanup on a new branch",
            description =
                    """
                    Applies plan_cleanup's safe patches on a new git branch \
                    (stealth/clean-<date>-<time>), one commit each, built in a temporary worktree: \
                    the user's checkout, branch and files are never touched. Runs the project's \
                    tests before and after and drops any patch that makes them worse. Needs a git \
                    repository with no uncommitted changes. Only call it after the user has agreed \
                    to the plan. Takes as long as the test suite. Returns the branch to review.\
                    """,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    CallToolResult applyCleanup(
            @McpToolParam(description = "Absolute path of the repository root.") String path,
            @McpToolParam(
                            description =
                                    "Also apply proven patches that move to a new major version."
                                            + " Default false; only if the user asks.",
                            required = false)
                    Boolean allowMajor,
            @McpToolParam(
                            description =
                                    "Also apply proven minor jumps past the versions the"
                                            + " framework (e.g. Spring Boot) manages. Default"
                                            + " false; only if the user accepts the risk.",
                            required = false)
                    Boolean allowMinor,
            @McpToolParam(
                            description =
                                    "How to run the tests, e.g. './mvnw -B -pl app test'. Default:"
                                            + " the project's Maven wrapper or mvn.",
                            required = false)
                    String testCommand,
            @McpToolParam(
                            description =
                                    "Don't run the tests. Default false; only if the user asks.",
                            required = false)
                    Boolean skipTests,
            @McpToolParam(
                            description =
                                    "Go ahead when the tests already fail, requiring no new"
                                            + " failures. Default false.",
                            required = false)
                    Boolean allowFailingTests) {
        return call(
                "apply_cleanup " + path,
                () -> {
                    Path root = StealthTools.directory(path);
                    RepoContext context = scans.context(root);
                    CleanupPlan plan = planner.plan(context, scans.scan(root, false).report());
                    CleanupResult result =
                            cleaner.apply(
                                    context,
                                    plan,
                                    new Cleaner.Options(
                                            Boolean.TRUE.equals(allowMajor),
                                            Boolean.TRUE.equals(allowMinor),
                                            Boolean.TRUE.equals(skipTests)
                                                    ? Optional.empty()
                                                    : Optional.of(tests(testCommand)),
                                            Boolean.TRUE.equals(allowFailingTests),
                                            Optional.empty()));
                    return CleanupJson.result(result);
                });
    }

    @McpTool(
            name = "verify_cleanup",
            title = "Verify changes",
            description =
                    """
                    Checks changes against a base commit: which findings are resolved, which are \
                    new, and the scores before and after, optionally running the tests. Call it \
                    after you change code to fix something (removing a hardcoded secret, upgrading \
                    or replacing a dependency, migrating call sites), and keep going until it \
                    reports green. With no base it compares the working tree with HEAD; pass e.g. \
                    base=main to check a branch.\
                    """,
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = true))
    CallToolResult verifyCleanup(
            @McpToolParam(description = "Absolute path of the repository root.") String path,
            @McpToolParam(
                            description =
                                    "Commit or branch to compare with. Default: HEAD (your"
                                            + " uncommitted changes).",
                            required = false)
                    String base,
            @McpToolParam(description = "Run the tests too. Default false.", required = false)
                    Boolean runTests) {
        return call(
                "verify_cleanup " + path,
                () -> {
                    Path root = StealthTools.directory(path);
                    return CleanupJson.verification(
                            verifier.verify(
                                    scans.context(root),
                                    Optional.ofNullable(base).filter(b -> !b.isBlank()),
                                    Boolean.TRUE.equals(runTests)
                                            ? Optional.of(tests(null))
                                            : Optional.empty()));
                });
    }

    private static TestRunner tests(String command) {
        Optional<List<String>> parsed =
                command == null || command.isBlank()
                        ? Optional.empty()
                        : Optional.of(Arrays.asList(command.strip().split("\\s+")));
        return new TestRunner(parsed, TestRunner.DEFAULT_TIMEOUT);
    }

    private static CallToolResult call(String description, ToolBody body) {
        Instant started = Instant.now();
        try {
            String json = body.run();
            LOG.info(
                    "{} in {} ms",
                    description,
                    Duration.between(started, Instant.now()).toMillis());
            return CallToolResult.builder().addTextContent(json).build();
        } catch (IllegalArgumentException | CleanException | ConfigException | IOException e) {
            LOG.info("{} failed: {}", description, e.getMessage());
            return CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CallToolResult.builder().addTextContent("Interrupted").isError(true).build();
        } catch (RuntimeException e) {
            LOG.warn("{} failed", description, e);
            return CallToolResult.builder()
                    .addTextContent("stealth failed: " + e)
                    .isError(true)
                    .build();
        }
    }

    @FunctionalInterface
    private interface ToolBody {
        String run() throws IOException, ConfigException, CleanException, InterruptedException;
    }
}
