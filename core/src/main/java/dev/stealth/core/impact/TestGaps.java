package dev.stealth.core.impact;

import dev.stealth.core.RepoContext;
import dev.stealth.core.clean.CleanException;
import dev.stealth.core.clean.TestRunner;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where a dependency is used, or where an upgrade breaks, that no test runs. Before a risky upgrade
 * those are the places to pin down with tests first, so that "the tests pass" afterwards means the
 * behaviour there didn't change. The tests run with JaCoCo (added on the command line, so the POM
 * isn't changed); call sites come from the compiled code the run leaves behind.
 */
public class TestGaps {

    static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.15";

    private final MavenModelLoader loader;
    private final UpgradeImpact impact;

    public TestGaps(MavenModelLoader loader, UpgradeImpact impact) {
        this.loader = loader;
        this.impact = impact;
    }

    /**
     * A call site no test runs.
     *
     * @param method the method to test, {@code Class#method}
     * @param testClass an existing test class for that class, if there is one
     */
    public record Gap(
            String where, String api, Optional<String> method, Optional<String> testClass) {}

    /**
     * @param to the upgrade's target version, when the sites are what it breaks; empty when they're
     *     every use of the dependency
     * @param sites how many call sites were checked
     * @param testStyle what the repository's tests use, to write new ones the same way
     */
    public record Result(
            String dependency,
            Optional<String> to,
            int sites,
            List<Gap> gaps,
            boolean testsPassed,
            List<String> failingTests,
            String command,
            List<String> testStyle,
            List<String> notes) {

        public int covered() {
            return sites - gaps.size();
        }
    }

    /**
     * @param command how to run the tests with coverage; empty uses the Maven wrapper (or mvn) with
     *     JaCoCo's prepare-agent and report goals around {@code test}
     */
    public Result analyze(
            RepoContext context,
            String groupId,
            String artifactId,
            Optional<String> to,
            Optional<List<String>> command)
            throws IOException, CleanException, InterruptedException {
        Path root = context.root();
        String key = groupId + ":" + artifactId;
        List<String> notes = new ArrayList<>();
        // Report even when tests fail, so coverage is still known
        List<String> chosen =
                command.orElseGet(
                        () ->
                                TestRunner.maven(
                                        root,
                                        List.of(
                                                "-Dmaven.test.failure.ignore=true",
                                                JACOCO + ":prepare-agent",
                                                "test",
                                                JACOCO + ":report")));
        TestRunner.Run run =
                new TestRunner(Optional.of(chosen), TestRunner.DEFAULT_TIMEOUT).run(root);
        Coverage coverage = Coverage.read(root);
        if (coverage.isEmpty()) {
            notes.add(
                    "No JaCoCo report was written: the build failed before testing, or the"
                            + " Surefire argLine overrides JaCoCo's agent (use @{argLine} in it).");
        }

        List<Site> sites = sites(context, groupId, artifactId, to);
        List<Gap> gaps = new ArrayList<>();
        for (Site site : sites) {
            String file = site.where().substring(0, site.where().lastIndexOf(':'));
            int line = Integer.parseInt(site.where().substring(site.where().lastIndexOf(':') + 1));
            if (!coverage.covers(file, line)) {
                gaps.add(new Gap(site.where(), site.api(), site.method(), testClass(root, file)));
            }
        }
        if (sites.isEmpty()) {
            notes.add(
                    to.isPresent()
                            ? "Nothing here uses what the upgrade removes or deprecates."
                            : "The compiled code doesn't reference " + key + " directly.");
        }
        return new Result(
                key,
                to,
                sites.size(),
                gaps,
                !run.timedOut() && run.failed().isEmpty() && !coverage.isEmpty(),
                List.copyOf(run.failed()),
                run.command(),
                testStyle(context),
                notes);
    }

    private record Site(String where, String api, Optional<String> method) {}

    /** The upgrade's breaking and deprecated usages, or every use of the dependency's classes. */
    private List<Site> sites(
            RepoContext context, String groupId, String artifactId, Optional<String> to)
            throws IOException {
        Map<String, Site> sites = new LinkedHashMap<>();
        if (to.isPresent()) {
            for (UpgradeImpact.Usage usage :
                    impact.analyze(context, groupId, artifactId, to.get()).usages()) {
                if (usage.where().contains("src/main/")) {
                    sites.putIfAbsent(
                            usage.where() + usage.api(),
                            new Site(usage.where(), usage.api(), usage.method()));
                }
            }
            return List.copyOf(sites.values());
        }
        String version =
                UpgradeImpact.currentVersion(context.get(loader), groupId + ":" + artifactId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                groupId
                                                        + ":"
                                                        + artifactId
                                                        + " isn't a dependency of this"
                                                        + " repository"));
        Path jar =
                loader.resolve(groupId, artifactId, version, "jar")
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "can't download "
                                                        + groupId
                                                        + ":"
                                                        + artifactId
                                                        + ":"
                                                        + version));
        Set<String> classes = ApiSurface.of(jar).classes().keySet();
        for (UsageScanner.Reference reference :
                UsageScanner.scan(context.root(), UsageScanner.classDirectories(context.root()))) {
            if (classes.contains(reference.owner()) && reference.source().contains("src/main/")) {
                String where = reference.source() + ":" + reference.line();
                String api =
                        reference.member() == null
                                ? UpgradeImpact.dotted(reference.owner())
                                : UpgradeImpact.member(reference.owner(), reference.member());
                sites.putIfAbsent(
                        where, new Site(where, api, Optional.ofNullable(reference.method())));
            }
        }
        return List.copyOf(sites.values());
    }

    /**
     * {@code src/main/java/p/Foo.java} → {@code src/test/java/p/FooTest.java} (or Tests, IT), if it
     * exists.
     */
    static Optional<String> testClass(Path root, String source) {
        if (!source.contains("src/main/java/") || !source.endsWith(".java")) {
            return Optional.empty();
        }
        String base =
                source.replace("src/main/java/", "src/test/java/").replaceFirst("\\.java$", "");
        for (String suffix : List.of("Test", "Tests", "IT")) {
            if (Files.isRegularFile(root.resolve(base + suffix + ".java"))) {
                return Optional.of(base + suffix + ".java");
            }
        }
        return Optional.empty();
    }

    /**
     * What the tests are written with: frameworks from the dependencies, Spring test slices in use.
     */
    List<String> testStyle(RepoContext context) throws IOException {
        Set<String> style = new LinkedHashSet<>();
        for (MavenModule module : context.get(loader).modules()) {
            module.dependencies()
                    .forEach(
                            d -> {
                                if (d.groupId().equals("org.junit.jupiter")) {
                                    style.add("JUnit 5");
                                } else if (d.key().equals("junit:junit")) {
                                    style.add("JUnit 4");
                                } else if (d.groupId().equals("org.mockito")) {
                                    style.add("Mockito");
                                } else if (d.key()
                                        .equals(
                                                "org.springframework.boot:spring-boot-starter-test")) {
                                    style.add("JUnit 5");
                                    style.add("Mockito");
                                    style.add("AssertJ");
                                }
                            });
        }
        try (Stream<Path> files = Files.walk(context.root())) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(
                                    f ->
                                            f.toString()
                                                    .contains(
                                                            "src"
                                                                    + java.io.File.separator
                                                                    + "test"))
                            .toList()) {
                String text = Files.readString(file);
                for (String annotation :
                        List.of(
                                "@SpringBootTest",
                                "@WebMvcTest",
                                "@DataJpaTest",
                                "@MockBean",
                                "@MockitoBean")) {
                    if (text.contains(annotation)) {
                        style.add(annotation);
                    }
                }
            }
        }
        return List.copyOf(style);
    }
}
