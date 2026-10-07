package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.hook.HookChecks;
import dev.stealth.core.secrets.SecretsAnalyzer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code stealth hook}: Claude Code's JSON in, a block decision (or nothing) out, never failing.
 */
class HookMainTest {

    private static final String TOKEN = "ghp_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5";

    @TempDir private Path repo;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(repo.resolve("README.md"), "hello\n");
        git("init", "--quiet");
        git("add", ".");
        git(
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@example.com",
                "commit",
                "--quiet",
                "-m",
                "init");
    }

    @Test
    void postEdit_fileWithANewSecret_blocksWithTheReason() throws Exception {
        Files.writeString(
                repo.resolve("Client.java"), "class Client { String t = \"" + TOKEN + "\"; }\n");

        int exitCode = run("post-edit", postToolUse("Client.java"));

        assertThat(exitCode).isZero();
        var decision = JsonMapper.builder().build().readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(decision.get("decision").asString()).isEqualTo("block");
        assertThat(decision.get("reason").asString())
                .startsWith(
                        "stealth: this edit introduced a problem:\n- Client.java:1: GitHub token")
                .endsWith("Fix it now, or tell the user why it's intended.");
    }

    @Test
    void postEdit_harmlessEdit_printsNothing() throws Exception {
        Files.writeString(repo.resolve("README.md"), "hello again\n");

        assertThat(run("post-edit", postToolUse("README.md"))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void stop_alreadyBlockedThisTurn_letsTheAgentFinish() throws Exception {
        Files.writeString(
                repo.resolve("Client.java"), "class Client { String t = \"" + TOKEN + "\"; }\n");

        int exitCode =
                run("stop", "{\"cwd\":" + quoted(repo.toString()) + ",\"stop_hook_active\":true}");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void stop_sessionAddedASecret_blocks() throws Exception {
        Files.writeString(
                repo.resolve("Client.java"), "class Client { String t = \"" + TOKEN + "\"; }\n");

        run("stop", "{\"cwd\":" + quoted(repo.toString()) + ",\"stop_hook_active\":false}");

        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"decision\":\"block\"")
                .contains("before you finish, fix what this session introduced");
    }

    @Test
    void anything_notAGitRepositoryOrBrokenInput_failsOpen(@TempDir Path elsewhere)
            throws Exception {
        assertThat(run("post-edit", "{\"cwd\":" + quoted(elsewhere.toString()) + "}")).isZero();
        assertThat(run("post-edit", "not json")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("stealth hook: skipped");
    }

    private int run(String event, String stdin) {
        HookChecks checks =
                new HookChecks(
                        new SecretsAnalyzer(Clock.systemUTC()),
                        (g, a, v) ->
                                new DependencyCheck.Result(
                                        g,
                                        a,
                                        true,
                                        v,
                                        v,
                                        Optional.empty(),
                                        Optional.of(true),
                                        List.of(),
                                        Optional.empty()),
                        Clock.systemUTC());
        return HookMain.run(
                new String[] {"hook", event},
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                () -> checks);
    }

    private String postToolUse(String file) {
        return "{\"cwd\":"
                + quoted(repo.toString())
                + ",\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":"
                + quoted(repo.resolve(file).toString())
                + "}}";
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    private void git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git failed: " + output);
        }
    }
}
