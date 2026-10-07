package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HooksCommandTest {

    @TempDir private Path repo;

    private final StringWriter out = new StringWriter();

    @Test
    void install_addsBothHooksAndKeepsExistingSettings() throws Exception {
        Path settings = repo.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(
                settings,
                """
                {"permissions":{"allow":["Bash(ls:*)"]},
                 "hooks":{"PostToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}]}}
                """);

        int exitCode = execute("install", repo.toString());

        JsonNode json = JsonMapper.builder().build().readTree(Files.readString(settings));
        assertThat(exitCode).isZero();
        assertThat(json.at("/permissions/allow/0").asString()).isEqualTo("Bash(ls:*)");
        assertThat(json.at("/hooks/PostToolUse")).hasSize(2);
        assertThat(json.at("/hooks/PostToolUse/1/matcher").asString())
                .isEqualTo("Edit|Write|MultiEdit");
        assertThat(json.at("/hooks/PostToolUse/1/hooks/0/command").asString())
                .isEqualTo("stealth hook post-edit");
        assertThat(json.at("/hooks/Stop/0/hooks/0/command").asString())
                .isEqualTo("stealth hook stop");
        assertThat(out.toString()).contains("Commit .claude/settings.json");
    }

    @Test
    void install_twice_doesntDuplicate() throws Exception {
        execute("install", repo.toString());
        execute("install", "--command", "java -jar stealth.jar", repo.toString());

        JsonNode json =
                JsonMapper.builder()
                        .build()
                        .readTree(Files.readString(repo.resolve(".claude/settings.json")));
        assertThat(json.at("/hooks/PostToolUse")).hasSize(1);
        assertThat(json.at("/hooks/Stop")).hasSize(1);
        assertThat(json.at("/hooks/Stop/0/hooks/0/command").asString())
                .isEqualTo("java -jar stealth.jar hook stop");
    }

    @Test
    void uninstall_removesOnlyStealthsHooks() throws Exception {
        Path settings = repo.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(
                settings,
                "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo"
                    + " hi\"}]}]}}");
        execute("install", repo.toString());

        execute("uninstall", repo.toString());

        JsonNode json = JsonMapper.builder().build().readTree(Files.readString(settings));
        assertThat(json.at("/hooks/PostToolUse")).hasSize(1);
        assertThat(json.at("/hooks/PostToolUse/0/hooks/0/command").asString()).isEqualTo("echo hi");
        assertThat(json.at("/hooks").has("Stop")).isFalse();
    }

    private int execute(String... args) {
        CommandLine commandLine = new CommandLine(new HooksCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
