package dev.stealth.core.clean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Runs a project's tests: its Maven wrapper if it has one, else {@code mvn}, or a command the user
 * gives. Failures are read from the Surefire reports, so a run can be compared with the baseline
 * test by test.
 */
public final class TestRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(20);

    private final Optional<List<String>> command;
    private final Duration timeout;

    /**
     * @param command the test command, or empty to use the project's Maven wrapper or {@code mvn}
     */
    public TestRunner(Optional<List<String>> command, Duration timeout) {
        this.command = command.map(List::copyOf);
        this.timeout = timeout;
    }

    /**
     * One test run.
     *
     * @param failed the failing tests, {@code Class#method}, from Surefire reports
     * @param output the end of the build output
     */
    public record Run(
            boolean passed,
            boolean timedOut,
            Set<String> failed,
            String command,
            Duration duration,
            String output) {

        /** Tests failing here that didn't fail in {@code baseline}. */
        public Set<String> newFailures(Run baseline) {
            Set<String> fresh = new TreeSet<>(failed);
            fresh.removeAll(baseline.failed);
            return fresh;
        }

        /** Whether this run is no worse than {@code baseline}. */
        public boolean noWorseThan(Run baseline) {
            if (passed) {
                return true;
            }
            // A failure without test reports (it didn't compile, say) is always worse
            return !failed.isEmpty() && newFailures(baseline).isEmpty() && !timedOut;
        }
    }

    public Run run(Path project) throws CleanException, InterruptedException {
        List<String> chosen = command.orElseGet(() -> maven(project));
        deleteReports(project);
        Instant started = Instant.now();
        Command.Result result;
        try {
            result = Command.run(project, timeout, chosen);
        } catch (IOException e) {
            throw new CleanException(
                    "can't run the tests with `"
                            + String.join(" ", chosen)
                            + "`: "
                            + e.getMessage()
                            + " (pass --test-command to say how)",
                    e);
        }
        return new Run(
                result.ok(),
                result.timedOut(),
                failures(project),
                String.join(" ", chosen),
                Duration.between(started, Instant.now()),
                result.tail(40));
    }

    /** The project's wrapper if it has one, else {@code mvn}, running {@code goals}. */
    public static List<String> maven(Path project, List<String> goals) {
        List<String> command = new java.util.ArrayList<>(maven(project).subList(0, 3));
        command.addAll(goals);
        return command;
    }

    /** The project's wrapper if it has one, else {@code mvn}; batch mode, no colour. */
    static List<String> maven(Path project) {
        String wrapper = Command.WINDOWS ? "mvnw.cmd" : "mvnw";
        Path local = project.resolve(wrapper);
        String program =
                Files.isRegularFile(local)
                        ? local.toAbsolutePath().toString()
                        : Command.WINDOWS ? "mvn.cmd" : "mvn";
        return List.of(program, "-B", "-Dstyle.color=never", "test");
    }

    /** Failing tests in every module's {@code target/surefire-reports}. */
    private static Set<String> failures(Path project) {
        Set<String> failed = new TreeSet<>();
        try (Stream<Path> files = Files.walk(project)) {
            for (Path report :
                    files.filter(f -> f.getFileName().toString().startsWith("TEST-"))
                            .filter(f -> f.getFileName().toString().endsWith(".xml"))
                            .filter(
                                    f ->
                                            f.getParent()
                                                    .endsWith(
                                                            Path.of("target", "surefire-reports")))
                            .toList()) {
                failed.addAll(failuresIn(report));
            }
        } catch (IOException e) {
            // No reports: the caller treats a failed run without them as worse than any baseline
        }
        return failed;
    }

    static Set<String> failuresIn(Path report) {
        Set<String> failed = new TreeSet<>();
        try (InputStream in = Files.newInputStream(report)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            String testcase = null;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (xml.getLocalName()) {
                    case "testcase" ->
                            testcase =
                                    xml.getAttributeValue(null, "classname")
                                            + "#"
                                            + xml.getAttributeValue(null, "name");
                    case "failure", "error" -> {
                        if (testcase != null) {
                            failed.add(testcase);
                        }
                    }
                    default -> {}
                }
            }
        } catch (IOException | XMLStreamException e) {
            // An unreadable report: count nothing from it
        }
        return failed;
    }

    private static void deleteReports(Path project) {
        try (Stream<Path> files = Files.walk(project)) {
            for (Path report :
                    files.filter(f -> f.getParent() != null)
                            .filter(
                                    f ->
                                            f.getParent()
                                                    .endsWith(
                                                            Path.of("target", "surefire-reports")))
                            .toList()) {
                Files.deleteIfExists(report);
            }
        } catch (IOException e) {
            // Stale reports only matter if the next run writes none
        }
    }
}
