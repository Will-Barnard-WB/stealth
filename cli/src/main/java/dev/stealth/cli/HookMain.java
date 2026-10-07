package dev.stealth.cli;

import dev.stealth.core.ConfigException;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.StealthConfigLoader;
import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.hook.HookChecks;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import dev.stealth.core.secrets.SecretsAnalyzer;
import dev.stealth.core.vuln.OsvClient;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code stealth hook post-edit|stop}: what Claude Code runs (see {@code stealth hooks install}).
 * Reads the hook's JSON from stdin and, if the edit or the session introduced a problem, prints
 * {@code {"decision":"block","reason":...}} so the agent fixes it. Runs without Spring, so it costs
 * little more than JVM startup, and always exits 0: stealth failing must never block the agent.
 */
final class HookMain {

    static final String POST_EDIT = "post-edit";
    static final String STOP = "stop";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HookMain() {}

    /** Builds the real checks: Maven Central, OSV and the HTTP cache, as the CLI uses them. */
    static HookChecks checks() {
        Clock clock = Clock.systemUTC();
        CachedHttpClient http = CachedHttpClient.create(HttpCache.defaultCache(), () -> false);
        MavenModelLoader loader = new MavenModelLoader(MavenResolverSettings.defaults());
        DependencyCheck check =
                new DependencyCheck(
                        new MavenCentralClient(http, MavenResolverSettings.MAVEN_CENTRAL),
                        new MavenCentralSearch(http, MavenCentralSearch.SEARCH),
                        new VulnerabilityAnalyzer(loader, new OsvClient(http, OsvClient.OSV)),
                        clock);
        return new HookChecks(new SecretsAnalyzer(clock), check::check, clock);
    }

    /**
     * @param args {@code hook <event>}
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return run(args, in, out, err, HookMain::checks);
    }

    static int run(
            String[] args,
            InputStream in,
            PrintStream out,
            PrintStream err,
            java.util.function.Supplier<HookChecks> checks) {
        String event = args.length > 1 ? args[1] : "";
        try {
            JsonNode input = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (event.equals(STOP) && input.path("stop_hook_active").asBoolean(false)) {
                // Already told once this turn: don't loop
                return 0;
            }
            Path cwd = Path.of(input.path("cwd").asString(System.getProperty("user.dir")));
            Optional<Path> top = HookChecks.repositoryTop(cwd);
            if (top.isEmpty()) {
                return 0;
            }
            StealthConfig config = config(top.get());
            List<String> problems =
                    switch (event) {
                        case POST_EDIT -> {
                            String file = input.path("tool_input").path("file_path").asString("");
                            yield file.isEmpty()
                                    ? List.of()
                                    : checks.get().afterEdit(top.get(), cwd.resolve(file), config);
                        }
                        case STOP -> checks.get().beforeStop(top.get(), config);
                        default -> {
                            err.println(
                                    "stealth hook: unknown event '"
                                            + event
                                            + "'; expected post-edit or stop");
                            yield List.of();
                        }
                    };
            if (!problems.isEmpty()) {
                ObjectNode decision = JSON.createObjectNode();
                decision.put("decision", "block");
                decision.put("reason", reason(event, problems));
                out.print(JSON.writeValueAsString(decision));
                out.flush();
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            err.println("stealth hook: skipped (" + e + ")");
            return 0;
        }
    }

    static String reason(String event, List<String> problems) {
        StringBuilder reason =
                new StringBuilder(
                        event.equals(STOP)
                                ? "stealth: before you finish, fix what this session introduced:\n"
                                : "stealth: this edit introduced a problem:\n");
        problems.forEach(p -> reason.append("- ").append(p).append('\n'));
        reason.append(
                event.equals(STOP)
                        ? "If one is intended, tell the user why instead of changing it."
                        : "Fix it now, or tell the user why it's intended.");
        return reason.toString();
    }

    private static StealthConfig config(Path top) {
        try {
            return StealthConfigLoader.load(top, Set.of(), Set.of()).config();
        } catch (ConfigException e) {
            return StealthConfig.defaults();
        }
    }
}
