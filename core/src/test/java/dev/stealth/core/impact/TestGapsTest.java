package dev.stealth.core.impact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test gaps on the compiled world from {@link UpgradeImpactTest}, with a stand-in "test run" that
 * writes the JaCoCo report a real run would: lines 9 and 10 of App.java covered, the rest not.
 */
class TestGapsTest {

    @TempDir private Path work;

    private Path repo;
    private TestGaps gaps;

    @BeforeEach
    void setUp() throws Exception {
        Path localRepository = work.resolve("m2");
        Path lib1 =
                CompiledWorld.compile(
                        work, UpgradeImpactTest.LIB_1, List.of(), work.resolve("lib1"));
        Path lib2 =
                CompiledWorld.compile(
                        work, UpgradeImpactTest.LIB_2, List.of(), work.resolve("lib2"));
        Path jar1 = CompiledWorld.jar(lib1, work.resolve("lib-1.0.jar"));
        CompiledWorld.install(localRepository, "1.0", jar1);
        CompiledWorld.install(
                localRepository, "2.0", CompiledWorld.jar(lib2, work.resolve("lib-2.0.jar")));
        repo = work.resolve("app");
        Files.createDirectories(repo.resolve("src/main/java/app"));
        Files.createDirectories(repo.resolve("src/test/java/app"));
        Files.writeString(repo.resolve("src/main/java/app/App.java"), UpgradeImpactTest.APP);
        Files.writeString(
                repo.resolve("src/test/java/app/AppTest.java"),
                "class AppTest { @org.junit.jupiter.api.Test void t() {} }");
        Files.writeString(
                repo.resolve("pom.xml"),
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                    </dependencies>
                </project>
                """);
        CompiledWorld.compile(
                work,
                Map.of("app/App.java", UpgradeImpactTest.APP),
                List.of(jar1),
                repo.resolve("target/classes"));
        MavenModelLoader loader =
                new MavenModelLoader(
                        new MavenResolverSettings(
                                localRepository, URI.create("http://localhost:1/"), true));
        gaps = new TestGaps(loader, new UpgradeImpact(loader));
    }

    @Test
    void analyze_upgradeSites_reportsTheOnesNoTestRunsWithTheMethodToTest() throws Exception {
        TestGaps.Result result = analyze(Optional.of("2.0"), "9,10");

        assertThat(result.sites()).isEqualTo(6);
        assertThat(result.gaps())
                .extracting(TestGaps.Gap::where)
                .containsExactlyInAnyOrder(
                        "src/main/java/app/App.java:11",
                        "src/main/java/app/App.java:12",
                        "src/main/java/app/App.java:13",
                        "src/main/java/app/App.java:18");
        assertThat(result.gaps())
                .filteredOn(g -> g.where().endsWith(":11"))
                .singleElement()
                .satisfies(
                        g -> {
                            assertThat(g.method()).contains("App#run");
                            assertThat(g.testClass()).contains("src/test/java/app/AppTest.java");
                        });
        assertThat(result.testsPassed()).isTrue();
    }

    @Test
    void analyze_everyUseOfTheLibrary_whenNoVersionIsGiven() throws Exception {
        TestGaps.Result result = analyze(Optional.empty(), "9,10,11,12,13");

        // The subclass declaration (16) and its override (18) use the library and no test runs them
        assertThat(result.gaps())
                .extracting(TestGaps.Gap::where)
                .containsExactly("src/main/java/app/App.java:16", "src/main/java/app/App.java:18");
    }

    @Test
    void analyze_noReport_saysWhy() throws Exception {
        TestGaps.Result result =
                gaps.analyze(
                        new RepoContext(repo, StealthConfig.defaults()),
                        "com.example",
                        "lib",
                        Optional.of("2.0"),
                        Optional.of(List.of(java(), "--version")));

        assertThat(result.testsPassed()).isFalse();
        assertThat(result.gaps()).hasSize(result.sites());
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("No JaCoCo report"));
    }

    private TestGaps.Result analyze(Optional<String> to, String coveredLines) throws Exception {
        Path script = work.resolve("Report.java");
        Files.writeString(
                script,
                """
                import java.nio.file.*;
                public class Report {
                    public static void main(String[] args) throws Exception {
                        StringBuilder lines = new StringBuilder();
                        for (int n = 1; n <= 20; n++) {
                            boolean covered = ("," + args[0] + ",").contains("," + n + ",");
                            lines.append("<line nr=\\"" + n + "\\" mi=\\"0\\" ci=\\"" + (covered ? 3 : 0) + "\\" mb=\\"0\\" cb=\\"0\\"/>");
                        }
                        Path report = Path.of("target/site/jacoco/jacoco.xml");
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, "<?xml version=\\"1.0\\"?><!DOCTYPE report PUBLIC \\"-//JACOCO//DTD Report 1.1//EN\\" \\"report.dtd\\"><report name=\\"app\\"><package name=\\"app\\"><sourcefile name=\\"App.java\\">" + lines + "</sourcefile></package></report>");
                    }
                }
                """);
        return gaps.analyze(
                new RepoContext(repo, StealthConfig.defaults()),
                "com.example",
                "lib",
                to,
                Optional.of(List.of(java(), script.toString(), coveredLines)));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
