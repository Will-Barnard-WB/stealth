package dev.stealth.core.clean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs an external program (git, the build) and keeps the end of its output. */
final class Command {

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** How much output to keep: the end of a failing build is what explains it. */
    private static final int MAX_OUTPUT = 16_000;

    private Command() {}

    record Result(int exitCode, String output, boolean timedOut) {

        boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** The last {@code lines} lines of output. */
        String tail(int lines) {
            List<String> all = output.lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    static Result run(Path directory, Duration timeout, List<String> command)
            throws IOException, InterruptedException {
        List<String> full = new ArrayList<>();
        String program = command.getFirst().toLowerCase(Locale.ROOT);
        if (WINDOWS && (program.endsWith(".cmd") || program.endsWith(".bat"))) {
            full.addAll(List.of("cmd.exe", "/c"));
        }
        full.addAll(command);
        Process process =
                new ProcessBuilder(full)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        Thread reader =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (InputStream in = process.getInputStream()) {
                                        byte[] buffer = new byte[8192];
                                        int read;
                                        while ((read = in.read(buffer)) >= 0) {
                                            synchronized (output) {
                                                output.append(
                                                        new String(
                                                                buffer,
                                                                0,
                                                                read,
                                                                StandardCharsets.UTF_8));
                                                if (output.length() > MAX_OUTPUT * 2) {
                                                    output.delete(0, output.length() - MAX_OUTPUT);
                                                }
                                            }
                                        }
                                    } catch (IOException e) {
                                        // The process ended; whatever was read is kept
                                    }
                                });
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(5_000);
        String text;
        synchronized (output) {
            text =
                    output.length() > MAX_OUTPUT
                            ? output.substring(output.length() - MAX_OUTPUT)
                            : output.toString();
        }
        return new Result(finished ? process.exitValue() : -1, text, !finished);
    }
}
