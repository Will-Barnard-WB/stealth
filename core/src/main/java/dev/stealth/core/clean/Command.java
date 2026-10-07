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
public final class Command {

    public static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** How much output to keep: the end of a failing build is what explains it. */
    private static final int MAX_OUTPUT = 16_000;

    private Command() {}

    /**
     * @param output standard output, plus standard error when they were merged
     * @param errors standard error when kept separate (empty when merged)
     */
    public record Result(int exitCode, String output, String errors, boolean timedOut) {

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** The last {@code lines} lines of output, then of errors. */
        public String tail(int lines) {
            List<String> all = new ArrayList<>(output.lines().toList());
            all.addAll(errors.lines().toList());
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    /** Runs {@code command} with standard error merged into the output, as a build log reads. */
    public static Result run(Path directory, Duration timeout, List<String> command)
            throws IOException, InterruptedException {
        return run(directory, timeout, command, true);
    }

    /**
     * @param mergeErrors false keeps standard error out of {@link Result#output()}: for output that
     *     gets parsed, which git warnings (such as Windows line-ending notices) would corrupt
     */
    public static Result run(
            Path directory, Duration timeout, List<String> command, boolean mergeErrors)
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
                        .redirectErrorStream(mergeErrors)
                        .start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();
        Thread outputReader = read(process.getInputStream(), output);
        Thread errorReader = mergeErrors ? null : read(process.getErrorStream(), errors);
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        outputReader.join(5_000);
        if (errorReader != null) {
            errorReader.join(5_000);
        }
        return new Result(
                finished ? process.exitValue() : -1, text(output), text(errors), !finished);
    }

    /** Reads {@code stream} into {@code into} on a virtual thread, keeping only the end. */
    private static Thread read(InputStream stream, StringBuilder into) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            try (InputStream in = stream) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = in.read(buffer)) >= 0) {
                                    synchronized (into) {
                                        into.append(
                                                new String(
                                                        buffer, 0, read, StandardCharsets.UTF_8));
                                        if (into.length() > MAX_OUTPUT * 2) {
                                            into.delete(0, into.length() - MAX_OUTPUT);
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                // The process ended; whatever was read is kept
                            }
                        });
    }

    private static String text(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.length() > MAX_OUTPUT
                    ? buffer.substring(buffer.length() - MAX_OUTPUT)
                    : buffer.toString();
        }
    }
}
