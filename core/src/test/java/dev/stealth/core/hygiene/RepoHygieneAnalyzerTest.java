package dev.stealth.core.hygiene;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.Location;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.SeededMavenRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepoHygieneAnalyzerTest {

    /** The fixtures' reference date. */
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @TempDir static Path seededRepository;

    private static MavenModelLoader loader;

    @TempDir private Path repo;

    @BeforeAll
    static void createLoader() {
        loader = new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
    }

    // fixtures/README.md: hygiene expectations
    @Test
    void analyze_boot2Legacy_flagsMissingCodeownersAndCi() throws Exception {
        assertThat(ruleIds(analyze(Fixture.BOOT2_LEGACY.path())))
                .containsExactlyInAnyOrder("hygiene/missing-codeowners", "hygiene/missing-ci");
    }

    @Test
    void analyze_boot4Clean_reportsNothing() throws Exception {
        assertThat(analyze(Fixture.BOOT4_CLEAN.path())).isEmpty();
    }

    @Test
    void analyze_eolRuntime_flagsMissingCodeownersCiAndTests() throws Exception {
        assertThat(ruleIds(analyze(Fixture.EOL_RUNTIME.path())))
                .containsExactlyInAnyOrder(
                        "hygiene/missing-codeowners",
                        "hygiene/missing-ci",
                        "hygiene/missing-tests");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS"})
    void analyze_codeownersInAnyStandardPlace_isNotMissing(String path) throws Exception {
        write(path, "* @team\n");

        assertThat(ruleIds(analyze(repo))).doesNotContain("hygiene/missing-codeowners");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".github/workflows/ci.yml",
                ".github/workflows/build.yaml",
                ".gitlab-ci.yml",
                "Jenkinsfile",
                "azure-pipelines.yml",
                ".circleci/config.yml",
                "bitbucket-pipelines.yml"
            })
    void analyze_ciConfiguration_isNotMissing(String path) throws Exception {
        write(path, "build\n");

        assertThat(ruleIds(analyze(repo))).doesNotContain("hygiene/missing-ci");
    }

    @Test
    void analyze_workflowsDirectoryWithoutWorkflows_isMissingCi() throws Exception {
        write(".github/workflows/README.md", "nothing here yet\n");

        assertThat(ruleIds(analyze(repo))).contains("hygiene/missing-ci");
    }

    @Test
    void analyze_moduleWithoutTests_pointsAtThatModule() throws Exception {
        write("pom.xml", parentPom("api", "service"));
        write("api/pom.xml", modulePom("api"));
        write("api/src/main/java/Api.java", "class Api {}");
        write("api/src/test/java/ApiTest.java", "class ApiTest {}");
        write("service/pom.xml", modulePom("service"));
        write("service/src/main/java/Service.java", "class Service {}");

        List<Finding> findings = only("hygiene/missing-tests", analyze(repo));

        assertThat(findings)
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.message())
                                    .isEqualTo(
                                            "Module service has code in src/main/java but no tests"
                                                    + " in src/test/java");
                            assertThat(finding.location())
                                    .isEqualTo(
                                            new Location(
                                                    Optional.of("service/pom.xml"),
                                                    OptionalInt.empty(),
                                                    Optional.of("service")));
                        });
    }

    @Test
    void analyze_projectWithoutMaven_checksTheRootForTests() throws Exception {
        write("src/main/java/App.java", "class App {}");

        assertThat(only("hygiene/missing-tests", analyze(repo)))
                .extracting(Finding::message)
                .containsExactly(
                        "The project has code in src/main/java but no tests in src/test/java");
    }

    @Test
    void analyze_oldBranches_listsThemOnceExcludingTheCurrentAndDefaultBranch() throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            RevCommit old = commit(git, "old.txt", NOW.minus(Period.ofDays(200)));
            git.branchCreate().setName("feature/abandoned").setStartPoint(old).call();
            git.branchCreate().setName("spike").setStartPoint(old).call();
            remoteBranch(git, "origin/feature/abandoned", old);
            RevCommit recent = commit(git, "recent.txt", NOW.minus(Period.ofDays(10)));
            git.branchCreate().setName("feature/active").setStartPoint(recent).call();
        }

        List<Finding> findings = only("hygiene/stale-branches", analyze(repo));

        assertThat(findings)
                .extracting(Finding::message)
                .containsExactly(
                        "2 branches with no commits in the last 90 days: feature/abandoned, spike");
        assertThat(findings.getFirst().location()).isEqualTo(Location.repository());
    }

    @Test
    void analyze_longerBranchThreshold_isNotStale() throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            RevCommit old = commit(git, "old.txt", NOW.minus(Period.ofDays(200)));
            git.branchCreate().setName("feature/abandoned").setStartPoint(old).call();
        }

        assertThat(only("hygiene/stale-branches", analyze(repo, Period.ofYears(1), Long.MAX_VALUE)))
                .isEmpty();
    }

    @Test
    void analyze_subdirectoryOfARepository_leavesBranchesAlone() throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            RevCommit old = commit(git, "service/old.txt", NOW.minus(Period.ofDays(200)));
            git.branchCreate().setName("feature/abandoned").setStartPoint(old).call();
        }

        assertThat(only("hygiene/stale-branches", analyze(repo.resolve("service")))).isEmpty();
    }

    @Test
    void analyze_notAGitRepository_stillRunsTheFileChecks() throws Exception {
        write("src/main/java/App.java", "class App {}");

        assertThat(ruleIds(analyze(repo)))
                .containsExactlyInAnyOrder(
                        "hygiene/missing-codeowners",
                        "hygiene/missing-ci",
                        "hygiene/missing-tests");
    }

    @Test
    void analyze_fileOverTheThreshold_isReportedWithItsSize() throws Exception {
        write("docs/manual.pdf", "x".repeat(3 * 1024 * 1024));
        write("README.md", "small");

        List<Finding> findings =
                only("hygiene/large-file", analyze(repo, Period.ofDays(90), 2L * 1024 * 1024));

        assertThat(findings)
                .extracting(Finding::message)
                .containsExactly("Large file committed (3.0 MB): docs/manual.pdf");
        assertThat(findings.getFirst().location().path()).contains("docs/manual.pdf");
    }

    @Test
    void analyze_largeFileIgnoredByGit_isNotReported() throws Exception {
        Git.init().setDirectory(repo.toFile()).call().close();
        write(".gitignore", "*.zip\n");
        write("build/output.zip", "x".repeat(3 * 1024 * 1024));

        assertThat(only("hygiene/large-file", analyze(repo, Period.ofDays(90), 2L * 1024 * 1024)))
                .isEmpty();
    }

    @Test
    void describe_periods_readNaturally() {
        assertThat(RepoHygieneAnalyzer.describe(Period.ofDays(90))).isEqualTo("90 days");
        assertThat(RepoHygieneAnalyzer.describe(Period.ofMonths(6))).isEqualTo("6 months");
        assertThat(RepoHygieneAnalyzer.describe(Period.ofYears(1))).isEqualTo("1 year");
    }

    private List<Finding> analyze(Path root) throws Exception {
        return analyze(
                root,
                RepoHygieneAnalyzer.DEFAULT_STALE_BRANCH_AFTER,
                RepoHygieneAnalyzer.DEFAULT_LARGE_FILE_BYTES);
    }

    private List<Finding> analyze(Path root, Period staleBranchAfter, long largeFileBytes)
            throws Exception {
        Files.createDirectories(root);
        RepoHygieneAnalyzer analyzer =
                new RepoHygieneAnalyzer(
                        loader, staleBranchAfter, largeFileBytes, Clock.fixed(NOW, ZoneOffset.UTC));
        return analyzer.analyze(new RepoContext(root, StealthConfig.defaults()));
    }

    private static List<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    private static List<Finding> only(String ruleId, List<Finding> findings) {
        return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    private void write(String path, String content) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private RevCommit commit(Git git, String path, Instant when) throws Exception {
        write(path, path);
        git.add().addFilepattern(".").call();
        PersonIdent author = new PersonIdent("Dev", "dev@example.com", when, ZoneOffset.UTC);
        return git.commit().setMessage(path).setAuthor(author).setCommitter(author).call();
    }

    private static void remoteBranch(Git git, String name, RevCommit commit) throws IOException {
        RefUpdate update = git.getRepository().updateRef("refs/remotes/" + name);
        update.setNewObjectId(commit);
        update.forceUpdate();
    }

    private static String parentPom(String... modules) {
        StringBuilder moduleList = new StringBuilder();
        for (String module : modules) {
            moduleList.append("<module>").append(module).append("</module>");
        }
        return "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                + "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>"
                + "<modules>"
                + moduleList
                + "</modules></project>";
    }

    private static String modulePom(String artifactId) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>com.example</groupId>"
                + "<artifactId>parent</artifactId><version>1</version></parent>"
                + "<artifactId>"
                + artifactId
                + "</artifactId></project>";
    }
}
