package dev.stealth.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code stealth hooks install|uninstall}: adds Claude Code hooks that check the agent's work as it
 * goes: after every edit (a vulnerable or invented dependency, a hardcoded secret) and before it
 * finishes (anything the session introduced). Written to the repository's {@code
 * .claude/settings.json}, so the whole team's agents get them once it's committed.
 */
@Component
@Command(
        name = "hooks",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = {
            "Add Claude Code hooks that check the agent's work as it goes: dependencies it adds and"
                    + " secrets it writes, after each edit and before it finishes."
        })
public class HooksCommand implements Callable<Integer> {

    static final String MARKER = "stealth hook ";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Spec private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "ACTION", description = "install or uninstall")
    private String action;

    @Parameters(
            index = "1",
            arity = "0..1",
            defaultValue = ".",
            paramLabel = "PATH",
            description = "Repository whose .claude/settings.json to change. Default: here.")
    private Path path;

    @Option(
            names = "--user",
            description = "Change ~/.claude/settings.json instead, for every repository.")
    private boolean user;

    @Option(
            names = "--command",
            paramLabel = "CMD",
            description = "How to run stealth from the hooks. Default: ${DEFAULT-VALUE}.")
    private String command = "stealth";

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path settings =
                user
                        ? Path.of(System.getProperty("user.home"), ".claude", "settings.json")
                        : path.toAbsolutePath()
                                .normalize()
                                .resolve(".claude")
                                .resolve("settings.json");
        ObjectNode json;
        try {
            json =
                    Files.isRegularFile(settings)
                            ? (ObjectNode) JSON.readTree(Files.readString(settings))
                            : JSON.createObjectNode();
        } catch (RuntimeException e) {
            err.println("stealth hooks: can't read " + settings + " as JSON: " + e.getMessage());
            return ExitCode.USAGE;
        }
        switch (action) {
            case "install" -> {
                remove(json);
                add(
                        json,
                        "PostToolUse",
                        "Edit|Write|MultiEdit",
                        command + " hook " + HookMain.POST_EDIT);
                add(json, "Stop", null, command + " hook " + HookMain.STOP);
                write(settings, json);
                out.println("Added stealth's hooks to " + settings + ".");
                out.println(
                        "After each edit, Claude Code is told if it added a vulnerable or unknown"
                                + " dependency or a hardcoded secret; before it finishes, about"
                                + " anything the session introduced.");
                if (!user) {
                    out.println("Commit .claude/settings.json so everyone's agent gets them.");
                }
            }
            case "uninstall" -> {
                boolean removed = remove(json);
                write(settings, json);
                out.println(
                        removed
                                ? "Removed stealth's hooks from " + settings + "."
                                : "No stealth hooks in " + settings + ".");
            }
            default -> {
                err.println("stealth hooks: expected install or uninstall, got '" + action + "'");
                return ExitCode.USAGE;
            }
        }
        out.flush();
        return ExitCode.OK;
    }

    /** Adds one hook group: {@code {"matcher":..., "hooks":[{"type":"command",...}]}}. */
    private static void add(ObjectNode settings, String event, String matcher, String command) {
        ObjectNode hooks =
                settings.has("hooks") && settings.get("hooks").isObject()
                        ? (ObjectNode) settings.get("hooks")
                        : settings.putObject("hooks");
        ArrayNode groups =
                hooks.has(event) && hooks.get(event).isArray()
                        ? (ArrayNode) hooks.get(event)
                        : hooks.putArray(event);
        ObjectNode group = groups.addObject();
        if (matcher != null) {
            group.put("matcher", matcher);
        }
        group.putArray("hooks")
                .addObject()
                .put("type", "command")
                .put("command", command)
                .put("timeout", 60);
    }

    /** Removes every hook whose command runs {@code stealth hook}, and groups left empty. */
    static boolean remove(ObjectNode settings) {
        if (!settings.has("hooks") || !settings.get("hooks").isObject()) {
            return false;
        }
        boolean removed = false;
        ObjectNode hooks = (ObjectNode) settings.get("hooks");
        for (String event : hooks.propertyNames().stream().toList()) {
            JsonNode groups = hooks.get(event);
            if (!groups.isArray()) {
                continue;
            }
            for (int g = groups.size() - 1; g >= 0; g--) {
                JsonNode inner = groups.get(g).path("hooks");
                if (!inner.isArray()) {
                    continue;
                }
                for (int h = inner.size() - 1; h >= 0; h--) {
                    if (inner.get(h).path("command").asString("").contains(MARKER)) {
                        ((ArrayNode) inner).remove(h);
                        removed = true;
                    }
                }
                if (inner.isEmpty()) {
                    ((ArrayNode) groups).remove(g);
                }
            }
            if (groups.isEmpty()) {
                hooks.remove(event);
            }
        }
        if (hooks.isEmpty()) {
            settings.remove("hooks");
        }
        return removed;
    }

    private static void write(Path settings, ObjectNode json) throws IOException {
        Files.createDirectories(settings.getParent());
        DefaultPrettyPrinter pretty =
                new DefaultPrettyPrinter(
                                Separators.createDefaultInstance()
                                        .withObjectNameValueSpacing(Separators.Spacing.AFTER))
                        .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                        .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        Files.writeString(
                settings,
                JSON.writer().with(pretty).writeValueAsString(json) + "\n",
                StandardCharsets.UTF_8);
    }
}
