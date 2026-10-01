package dev.stealth.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Runs other programs (the {@code claude} CLI). A bean so tests can replace it. */
@Component
public class ProcessRunner {

    /** Exit code and combined stdout and stderr. */
    public record Result(int exitCode, String output) {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** Where {@code program} is on {@code PATH}, if it's there. */
    public Optional<Path> find(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<String> names =
                WINDOWS
                        ? List.of(program + ".cmd", program + ".exe", program + ".bat", program)
                        : List.of(program);
        for (String directory : path.split(File.pathSeparator)) {
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public Result run(Path program, List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        String name = program.getFileName().toString().toLowerCase(Locale.ROOT);
        if (WINDOWS && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            // npm installs claude as a .cmd script, which only cmd.exe can run
            command.addAll(List.of("cmd.exe", "/c"));
        }
        command.add(program.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(-1, output + "\n(timed out)");
        }
        return new Result(process.exitValue(), output);
    }
}
