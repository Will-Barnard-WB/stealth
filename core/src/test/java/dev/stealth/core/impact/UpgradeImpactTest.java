package dev.stealth.core.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two versions of a small library, compiled here, in a local Maven repository on disk, and an app
 * compiled against the first. Everything runs offline, with real class files.
 */
class UpgradeImpactTest {

    private static final Map<String, String> LIB_1 =
            Map.of(
                    "lib/Api.java",
                    """
                    package lib;
                    public class Api {
                        public static String old() { return "old"; }
                        public static String kept() { return "kept"; }
                        public void changed(String value) {}
                        protected void hook(String value) {}
                    }
                    """,
                    "lib/Gone.java",
                    "package lib; public class Gone {}",
                    "lib/Moving.java",
                    "package lib; public class Moving {}");

    private static final Map<String, String> LIB_2 =
            Map.of(
                    "lib/Api.java",
                    """
                    package lib;
                    public class Api {
                        @Deprecated public static String kept() { return "kept"; }
                        public void changed(int value) {}
                        protected void hook(int value) {}
                    }
                    """,
                    "lib/moved/Moving.java",
                    "package lib.moved; public class Moving {}");

    private static final String APP =
            """
            package app;

            import lib.Api;
            import lib.Gone;
            import lib.Moving;

            public class App {
                public String run() {
                    String a = Api.old();
                    String b = Api.kept();
                    new Api().changed("x");
                    Object gone = new Gone();
                    return a + b + gone + new Moving();
                }

                static class Custom extends Api {
                    @Override
                    protected void hook(String value) {}
                }
            }
            """;

    @TempDir private Path work;

    private Path repo;
    private UpgradeImpact impact;

    @BeforeEach
    void setUp() throws Exception {
        Path localRepository = work.resolve("m2");
        Path lib1 = compile(LIB_1, List.of(), work.resolve("lib1"));
        Path lib2 = compile(LIB_2, List.of(), work.resolve("lib2"));
        install(localRepository, "1.0", jar(lib1, work.resolve("lib-1.0.jar")));
        install(localRepository, "2.0", jar(lib2, work.resolve("lib-2.0.jar")));

        repo = work.resolve("app");
        Files.createDirectories(repo.resolve("src/main/java/app"));
        Files.writeString(repo.resolve("src/main/java/app/App.java"), APP);
        Files.writeString(
                repo.resolve("pom.xml"),
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        impact =
                new UpgradeImpact(
                        new MavenModelLoader(
                                new MavenResolverSettings(
                                        localRepository, URI.create("http://localhost:1/"), true)));
    }

    @Test
    void analyze_compiledApp_findsEachBrokenCallWithItsLineAndAHint() throws Exception {
        compileApp(work.resolve("lib-1.0.jar"));

        UpgradeImpact.Result result = analyze("2.0");

        assertThat(result.from()).isEqualTo("1.0");
        assertThat(result.precise()).isTrue();
        assertThat(result.usages())
                .extracting(u -> u.kind() + " " + u.where() + " " + u.api())
                .contains(
                        "REMOVED src/main/java/app/App.java:9 lib.Api#old()",
                        "REMOVED src/main/java/app/App.java:11 lib.Api#changed(String)",
                        "REMOVED src/main/java/app/App.java:12 lib.Gone",
                        "REMOVED src/main/java/app/App.java:13 lib.Moving",
                        "DEPRECATED src/main/java/app/App.java:10 lib.Api#kept()",
                        // An override whose signature changed in the library: no longer overrides
                        "REMOVED src/main/java/app/App.java:18 lib.Api#hook(String)");
        assertThat(result.usages())
                .filteredOn(u -> u.api().equals("lib.Api#changed(String)"))
                .first()
                .satisfies(u -> assertThat(u.hint()).contains("now changed(int)"));
        assertThat(result.usages())
                .filteredOn(u -> u.api().equals("lib.Moving"))
                .first()
                .satisfies(u -> assertThat(u.hint()).contains("moved to lib.moved.Moving?"));
        // Removed first, so the agent starts with what won't compile
        assertThat(result.usages().getFirst().kind()).isEqualTo(UpgradeImpact.Kind.REMOVED);
        assertThat(result.breaking()).isEqualTo(5);
    }

    @Test
    void analyze_notCompiled_matchesImportsAndSaysItsApproximate() throws Exception {
        UpgradeImpact.Result result = analyze("2.0");

        assertThat(result.precise()).isFalse();
        assertThat(result.usages())
                .extracting(u -> u.where() + " " + u.api())
                .containsExactlyInAnyOrder(
                        "src/main/java/app/App.java:4 lib.Gone",
                        "src/main/java/app/App.java:5 lib.Moving");
        assertThat(result.notes()).singleElement().asString().contains("./mvnw compile");
    }

    @Test
    void analyze_dependencyNotUsed_saysSo() {
        assertThatThrownBy(
                        () ->
                                impact.analyze(
                                        new RepoContext(repo, StealthConfig.defaults()),
                                        "com.example",
                                        "other",
                                        "2.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("com.example:other isn't a dependency of this repository");
    }

    @Test
    void analyze_versionThatIsntPublished_saysSo() {
        assertThatThrownBy(() -> analyze("9.9"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("can't download com.example:lib:9.9");
    }

    @Test
    void member_readableSignatures() {
        assertThat(UpgradeImpact.member("lib/Api", "changed(Ljava/lang/String;I)V"))
                .isEqualTo("lib.Api#changed(String, int)");
        assertThat(UpgradeImpact.member("lib/Api", "<init>()V")).isEqualTo("lib.Api#new()");
        assertThat(UpgradeImpact.member("lib/Api$Inner", "COUNT:I"))
                .isEqualTo("lib.Api.Inner#COUNT");
    }

    private UpgradeImpact.Result analyze(String to) throws IOException {
        return impact.analyze(
                new RepoContext(repo, StealthConfig.defaults()), "com.example", "lib", to);
    }

    private void compileApp(Path libJar) throws IOException {
        compile(Map.of("app/App.java", APP), List.of(libJar), repo.resolve("target/classes"));
    }

    /**
     * Compiles {@code sources} (path → code) with debug info, so class files carry line numbers.
     */
    private Path compile(Map<String, String> sources, List<Path> classpath, Path output)
            throws IOException {
        Path sourceDir = Files.createTempDirectory(work, "src");
        List<String> arguments = new ArrayList<>(List.of("-g", "-d", output.toString()));
        if (!classpath.isEmpty()) {
            arguments.addAll(List.of("-cp", classpath.getFirst().toString()));
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, arguments.toArray(String[]::new));
        assertThat(status).as("javac").isZero();
        return output;
    }

    private static Path jar(Path classes, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out);
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jarOut.putNextEntry(
                        new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jarOut.write(Files.readAllBytes(file));
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private static void install(Path localRepository, String version, Path jar) throws IOException {
        Path directory = localRepository.resolve("com/example/lib/" + version);
        Files.createDirectories(directory);
        Files.copy(jar, directory.resolve("lib-" + version + ".jar"));
        Files.writeString(
                directory.resolve("lib-" + version + ".pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                        + "<artifactId>lib</artifactId><version>"
                        + version
                        + "</version></project>");
    }
}
