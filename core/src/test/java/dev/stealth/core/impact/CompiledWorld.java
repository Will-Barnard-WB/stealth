package dev.stealth.core.impact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
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

/** Compiles small libraries and apps in tests, and installs them into a local Maven repository. */
final class CompiledWorld {

    private CompiledWorld() {}

    /**
     * Compiles {@code sources} (path → code) with debug info, so class files carry line numbers.
     */
    static Path compile(Path work, Map<String, String> sources, List<Path> classpath, Path output)
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

    static Path jar(Path classes, Path jar) throws IOException {
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

    static void install(Path localRepository, String version, Path jar) throws IOException {
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
