package dev.stealth.core.clean;

import dev.stealth.core.Advisory;
import dev.stealth.core.AnalyzerResult;
import dev.stealth.core.AnalyzerStatus;
import dev.stealth.core.Category;
import dev.stealth.core.DoctorReport;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import dev.stealth.core.maven.DependencyNode;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import dev.stealth.core.maven.MavenResolverSettings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A small Maven world on disk, so the real loader, editor and proof run offline. The app inherits
 * {@code com.example:parent}, which manages {@code lib} through {@code lib.version} and imports
 * {@code com.example:bom} through {@code bom.version}; {@code starter} brings in {@code lib},
 * {@code fam-a} (from the BOM) and {@code loose} (unmanaged) transitively. A fake OSV ({@link
 * #VULNERABLE}) says what's vulnerable.
 */
final class MavenWorld {

    /** What our fake OSV knows: vulnerable {@code g:a@version} → advisory ids. */
    private static final Map<String, List<String>> VULNERABLE =
            Map.of(
                    "com.example:direct@1.0", List.of("ADV-D"),
                    "com.example:lib@1.0", List.of("ADV-L", "ADV-L2"),
                    "com.example:lib@1.1", List.of("ADV-L2"),
                    "com.example:fam-a@1.0", List.of("ADV-F"),
                    // Moving the whole BOM to 1.1 brings this in, so that patch must be rejected
                    "com.example:fam-b@1.1", List.of("ADV-NEW"),
                    // Its fix (1.9) was never published, like Spring's commercial-only 5.3.4x
                    "com.example:fam-b@1.0", List.of("ADV-GHOST"),
                    "com.example:loose@1.0", List.of("ADV-X"));

    final MavenModelLoader loader;
    final PatchPlanner planner;

    private MavenWorld(Path localRepository) {
        loader =
                new MavenModelLoader(
                        new MavenResolverSettings(
                                localRepository, URI.create("http://localhost:1/"), true));
        planner =
                new PatchPlanner(
                        loader,
                        context ->
                                vulnerabilities(context.get(loader)).stream()
                                        .map(v -> v.substring(0, v.lastIndexOf('@')))
                                        .collect(Collectors.toSet()));
    }

    /**
     * Writes the parent, BOMs and artifacts to {@code localRepository} and the app to {@code app}.
     */
    static MavenWorld create(Path localRepository, Path app) throws IOException {
        writeMavenWorld(localRepository, app);
        return new MavenWorld(localRepository);
    }

    /** Doctor's report for the app: its vulnerabilities as findings. */
    DoctorReport report(RepoContext context) {
        List<Finding> findings =
                vulnerabilities(context.get(loader)).stream().map(MavenWorld::finding).toList();
        return new DoctorReport(
                findings,
                List.of(
                        new AnalyzerResult(
                                "vuln",
                                Category.SECURITY,
                                AnalyzerStatus.OK,
                                Duration.ZERO,
                                Optional.empty())));
    }

    /**
     * Fake OSV: what in the resolved tree is in {@link #VULNERABLE}, as {@code g:a ADV@version}
     * (the planner's check drops the version; findings need it).
     */
    private static Set<String> vulnerabilities(MavenProjectModel model) {
        Set<String> found = new HashSet<>();
        for (MavenModule module : model.modules()) {
            for (DependencyNode root : module.dependencyTree()) {
                root.walk(
                        path -> {
                            DependencyNode node = path.getLast();
                            String coordinates = node.groupId() + ":" + node.artifactId();
                            VULNERABLE
                                    .getOrDefault(coordinates + "@" + node.version(), List.of())
                                    .forEach(
                                            advisory ->
                                                    found.add(
                                                            coordinates
                                                                    + " "
                                                                    + advisory
                                                                    + "@"
                                                                    + node.version()));
                        });
            }
        }
        return found;
    }

    /**
     * A doctor finding for {@code g:a ADVISORY@version}, with the fixed version our fake OSV
     * implies.
     */
    private static Finding finding(String vulnerability) {
        String[] parts = vulnerability.split("[ @]");
        String coordinates = parts[0];
        String advisory = parts[1];
        String version = parts[2];
        String fixed =
                switch (advisory) {
                    case "ADV-D", "ADV-L", "ADV-F" -> "1.1";
                    case "ADV-L2" -> "2.0";
                    case "ADV-X" -> "1.2";
                    case "ADV-GHOST" -> "1.9";
                    default -> "9.9";
                };
        return new Finding(
                PatchPlanner.RULE,
                Category.SECURITY,
                Severity.HIGH,
                coordinates + " " + version + ": " + advisory,
                new Location(
                        Optional.of("pom.xml"), java.util.OptionalInt.empty(), Optional.of("")),
                List.of(),
                Optional.of("pkg:maven/" + coordinates.replace(':', '/') + "@" + version),
                Optional.of(
                        new Advisory(
                                advisory, List.of(), OptionalDouble.empty(), Optional.empty())),
                Optional.of(new Remediation(Optional.of(fixed), Optional.empty())),
                Fingerprints.of(PatchPlanner.RULE, coordinates, advisory));
    }

    static final String APP_POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <relativePath/>
                </parent>
                <artifactId>app</artifactId>
                <properties>
                    <direct.version>1.0</direct.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct</artifactId>
                        <version>${direct.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>starter</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static void writeMavenWorld(Path localRepository, Path app) throws IOException {
        Files.writeString(app.resolve("pom.xml"), APP_POM);
        pom(
                localRepository,
                "parent",
                "1.0",
                """
                <packaging>pom</packaging>
                <properties>
                    <lib.version>1.0</lib.version>
                    <bom.version>1.0</bom.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>starter</artifactId>
                            <version>1.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>${lib.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>bom</artifactId>
                            <version>${bom.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);
        for (String version : List.of("1.0", "1.1")) {
            pom(
                    localRepository,
                    "bom",
                    version,
                    """
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>fam-a</artifactId>
                                <version>%1$s</version>
                            </dependency>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>fam-b</artifactId>
                                <version>%1$s</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    """
                            .formatted(version));
        }
        pom(
                localRepository,
                "starter",
                "1.0",
                """
                <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>fam-a</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>fam-b</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>loose</artifactId><version>1.0</version></dependency>
                </dependencies>
                """);
        for (String[] artifact :
                List.of(
                        new String[] {"direct", "1.0", "1.1"},
                        new String[] {"lib", "1.0", "1.1", "2.0"},
                        new String[] {"fam-a", "1.0", "1.1"},
                        new String[] {"fam-b", "1.0", "1.1"},
                        new String[] {"loose", "1.0", "1.2"})) {
            for (int i = 1; i < artifact.length; i++) {
                pom(localRepository, artifact[0], artifact[i], "");
            }
        }
    }

    private static void pom(Path localRepository, String artifactId, String version, String body)
            throws IOException {
        Path directory = localRepository.resolve("com/example/" + artifactId + "/" + version);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(artifactId + "-" + version + ".pom"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                %s
                </project>
                """
                        .formatted(artifactId, version, body));
    }
}
