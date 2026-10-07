package dev.stealth.core.clean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Applying patches on a branch, in a real git repository, with a stand-in test suite. */
class CleanerTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-10-07T14:12:00Z"), ZoneOffset.UTC);

    @TempDir private Path localRepository;
    @TempDir private Path app;
    @TempDir private Path tools;

    private MavenWorld world;
    private RepoContext context;
    private Cleaner cleaner;

    @BeforeEach
    void setUp() throws Exception {
        world = MavenWorld.create(localRepository, app);
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
        context = new RepoContext(app, StealthConfig.defaults());
        cleaner = new Cleaner(world.planner, NOW);
    }

    @Test
    void apply_passingTests_commitsEachSafePatchOnANewBranchAndLeavesTheCheckoutAlone()
            throws Exception {
        String branchBefore = git("rev-parse", "--abbrev-ref", "HEAD");

        CleanupResult result = cleaner.apply(context, plan(), options(testsPassingUnless("NEVER")));

        assertThat(result.branch()).contains("stealth/clean-20261007-1412");
        assertThat(result.applied()).hasSize(4);
        assertThat(result.failed()).isEmpty();
        assertThat(result.vulnerabilitiesBefore()).isEqualTo(7);
        assertThat(result.vulnerabilitiesAfter()).isEqualTo(2);
        assertThat(result.baseline()).hasValueSatisfying(r -> assertThat(r.passed()).isTrue());
        // The user's checkout is exactly as it was
        assertThat(Files.readString(app.resolve("pom.xml"))).isEqualTo(MavenWorld.APP_POM);
        assertThat(git("rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(branchBefore);
        assertThat(git("status", "--porcelain")).isEmpty();
        assertThat(git("worktree", "list")).doesNotContain("stealth-clean-");
        // The branch has one commit per patch, and the patched POM
        assertThat(git("log", "--format=%s", "stealth/clean-20261007-1412").lines())
                .hasSize(5)
                .filteredOn(s -> s.startsWith("stealth clean: "))
                .hasSize(4);
        assertThat(git("show", "stealth/clean-20261007-1412:pom.xml"))
                .contains("<direct.version>1.1</direct.version>")
                .contains("<lib.version>1.0.1</lib.version>")
                .contains("<artifactId>loose</artifactId>")
                .contains("<artifactId>fam-a</artifactId>");
    }

    @Test
    void apply_byDefault_leavesOutMinorJumpsPastWhatTheFrameworkManages() throws Exception {
        CleanupResult result =
                cleaner.apply(
                        context,
                        plan(),
                        new Cleaner.Options(
                                false,
                                false,
                                testsPassingUnless("NEVER"),
                                false,
                                Optional.empty()));

        assertThat(result.applied())
                .extracting(a -> a.patch().edit())
                .containsExactlyInAnyOrder(
                        new PomEdit.SetVersion("pom.xml", 12, "1.0", "1.1"),
                        new PomEdit.SetProperty("pom.xml", "lib.version", "1.0.1"),
                        new PomEdit.PinVersion("pom.xml", "com.example", "loose", "1.0.2"));
    }

    @Test
    void apply_patchThatBreaksTheTests_isDroppedAndTheRestKept() throws Exception {
        CleanupResult result =
                cleaner.apply(
                        context,
                        plan(),
                        options(testsPassingUnless("<artifactId>loose</artifactId>")));

        assertThat(result.applied()).hasSize(3);
        assertThat(result.failed())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.patch().edit())
                                    .isEqualTo(
                                            new PomEdit.PinVersion(
                                                    "pom.xml", "com.example", "loose", "1.2"));
                            assertThat(f.reason()).startsWith("the build failed");
                        });
        assertThat(git("show", result.branch().orElseThrow() + ":pom.xml"))
                .doesNotContain("<artifactId>loose</artifactId>")
                .contains("<lib.version>1.0.1</lib.version>");
    }

    @Test
    void apply_testsFailingBeforeAnyChange_refusesAndLeavesNoBranch() throws Exception {
        assertThatThrownBy(
                        () ->
                                cleaner.apply(
                                        context, plan(), options(testsPassingUnless("<project>"))))
                .isInstanceOf(CleanException.class)
                .hasMessageContaining("the tests fail before any change");

        assertThat(git("branch", "--list", "stealth/*")).isEmpty();
        assertThat(git("worktree", "list")).doesNotContain("stealth-clean-");
    }

    @Test
    void apply_uncommittedChanges_refuses() throws Exception {
        Files.writeString(app.resolve("pom.xml"), MavenWorld.APP_POM + "<!-- edited -->\n");

        assertThatThrownBy(() -> cleaner.apply(context, plan(), options(Optional.empty())))
                .isInstanceOf(CleanException.class)
                .hasMessageContaining("uncommitted changes");
    }

    @Test
    void apply_notAGitRepository_refuses(@TempDir Path elsewhere) throws Exception {
        Path copy = elsewhere.resolve("app");
        Files.createDirectories(copy);
        Files.writeString(copy.resolve("pom.xml"), MavenWorld.APP_POM);
        RepoContext notGit = new RepoContext(copy, StealthConfig.defaults());

        assertThatThrownBy(() -> cleaner.apply(notGit, plan(), options(Optional.empty())))
                .isInstanceOf(CleanException.class)
                .hasMessageContaining("isn't in a git repository");
    }

    @Test
    void chosen_includeMajor_replacesTheSameMajorPatchForTheSameEdit() throws Exception {
        List<Patch> safe = Cleaner.chosen(plan(), false, false);
        List<Patch> withMajor = Cleaner.chosen(plan(), true, false);

        assertThat(safe)
                .extracting(p -> p.edit())
                .contains(new PomEdit.SetProperty("pom.xml", "lib.version", "1.0.1"));
        assertThat(withMajor)
                .extracting(p -> p.edit())
                .contains(new PomEdit.SetProperty("pom.xml", "lib.version", "2.0"))
                .doesNotContain(new PomEdit.SetProperty("pom.xml", "lib.version", "1.0.1"));
        // Line edits come first, so insertions can't move the lines they point at
        assertThat(safe.getFirst().edit()).isInstanceOf(PomEdit.SetVersion.class);
    }

    @Test
    void message_saysWhatItClearsAndThatItWasProven() throws Exception {
        Patch lib =
                plan().patches().stream()
                        .filter(
                                p ->
                                        p.edit()
                                                .equals(
                                                        new PomEdit.SetProperty(
                                                                "pom.xml", "lib.version", "1.0.1")))
                        .findFirst()
                        .orElseThrow();

        assertThat(Cleaner.message(lib))
                .startsWith(
                        "stealth clean: Set <lib.version>1.0.1</lib.version>\n\n"
                                + "Clears 1 known vulnerability:\n"
                                + "- com.example:lib ADV-L\n")
                .endsWith("no new known vulnerabilities.");
    }

    private CleanupPlan plan() throws Exception {
        return world.planner.plan(context, world.report(context));
    }

    /** Every proven same-major patch, including the ones that need review. */
    private static Cleaner.Options options(Optional<TestRunner> tests) {
        return new Cleaner.Options(false, true, tests, false, Optional.empty());
    }

    /** A stand-in test suite: fails when the project's pom.xml contains {@code text}. */
    private Optional<TestRunner> testsPassingUnless(String text) throws IOException {
        Path script = tools.resolve("FailIf.java");
        Files.writeString(
                script,
                """
                public class FailIf {
                    public static void main(String[] args) throws Exception {
                        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
                        System.exit(pom.contains(args[0]) ? 1 : 0);
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return Optional.of(
                new TestRunner(
                        Optional.of(List.of(java, script.toString(), text)),
                        Duration.ofMinutes(1)));
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(app.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output.strip();
    }
}
