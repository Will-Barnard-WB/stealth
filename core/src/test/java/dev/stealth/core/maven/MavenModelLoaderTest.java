package dev.stealth.core.maven;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.Fixture;
import dev.stealth.core.Location;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@WireMockTest
class MavenModelLoaderTest {

    @TempDir static Path seededRepository;

    private static MavenProjectModel multiModule;
    private static MavenProjectModel boot2Legacy;

    @BeforeAll
    static void loadFixtures() {
        MavenModelLoader loader =
                new MavenModelLoader(SeededMavenRepository.extractTo(seededRepository));
        multiModule = loader.load(Fixture.MULTI_MODULE.path());
        boot2Legacy = loader.load(Fixture.BOOT2_LEGACY.path());
    }

    // The effective-versions table in fixtures/README.md
    @ParameterizedTest
    @CsvSource({
        "api,     com.fasterxml.jackson.core:jackson-databind, 2.22.3,     pom.xml,         21,"
                + " BOM",
        "api,     org.slf4j:slf4j-api,                         2.0.20,     pom.xml,         23,"
                + " MANAGED",
        "service, com.google.guava:guava,                      33.6.0-jre, service/pom.xml, 17,"
                + " MANAGED",
        "service, org.apache.commons:commons-lang3,            3.20.0,     pom.xml,         48,"
                + " MANAGED",
        "app,     com.google.guava:guava,                      33.7.2-jre, pom.xml,         22,"
                + " MANAGED",
        "app,     org.apache.commons:commons-lang3,            3.19.0,     app/pom.xml,     27,"
                + " DECLARED",
        "app,     org.slf4j:slf4j-api,                         2.0.20,     pom.xml,         23,"
                + " MANAGED",
    })
    void load_multiModule_resolvesEffectiveVersionAndWhereItIsSet(
            String module,
            String dependency,
            String version,
            String path,
            int line,
            VersionSource source) {
        ResolvedDependency resolved = dependency(multiModule, module, dependency);

        assertThat(resolved.version()).isEqualTo(version);
        assertThat(resolved.declaredAt().flatMap(Location::path)).contains(path);
        assertThat(resolved.declaredAt().map(Location::line)).contains(OptionalInt.of(line));
        assertThat(resolved.versionSource()).isEqualTo(source);
        assertThat(resolved.internal()).isFalse();
    }

    @Test
    void load_multiModule_sameDeclarationGivesSameLocationInEveryModule() {
        assertThat(dependency(multiModule, "api", "org.slf4j:slf4j-api").declaredAt())
                .isEqualTo(dependency(multiModule, "app", "org.slf4j:slf4j-api").declaredAt())
                .contains(
                        new Location(Optional.of("pom.xml"), OptionalInt.of(23), Optional.of("")));
    }

    @Test
    void load_multiModule_marksModulesOfTheRepositoryAsInternal() {
        ResolvedDependency api = dependency(multiModule, "service", "com.example.multi:api");

        assertThat(api.internal()).isTrue();
        assertThat(api.versionSource()).isEqualTo(VersionSource.INTERNAL);
        assertThat(api.declaredAt()).isEmpty();
    }

    @Test
    void load_multiModule_listsRootThenModulesInDeclarationOrder() {
        assertThat(multiModule.modules())
                .extracting(MavenModule::directory, MavenModule::pomPath)
                .containsExactly(
                        tuple("", "pom.xml"),
                        tuple("api", "api/pom.xml"),
                        tuple("service", "service/pom.xml"),
                        tuple("app", "app/pom.xml"));
        assertThat(multiModule.warnings()).isEmpty();
    }

    @Test
    void load_multiModule_readsJavaReleaseFromRootProperty() {
        assertThat(multiModule.module("app").flatMap(MavenModule::javaVersion))
                .contains(
                        new DeclaredVersion(
                                "21",
                                Optional.of(
                                        new Location(
                                                Optional.of("pom.xml"),
                                                OptionalInt.of(19),
                                                Optional.of("")))));
    }

    @Test
    void load_multiModule_treeIncludesInternalModulesAndTheirDependencies() {
        List<String> paths = paths(multiModule.module("app").orElseThrow());

        assertThat(paths)
                .contains(
                        "com.example.multi:service",
                        "com.example.multi:service > com.example.multi:api",
                        "com.example.multi:service > com.example.multi:api"
                                + " > com.fasterxml.jackson.core:jackson-databind");
    }

    @Test
    void load_boot2Legacy_resolvesStarterVersionsFromTheBootParent() {
        ResolvedDependency web =
                dependency(boot2Legacy, "", "org.springframework.boot:spring-boot-starter-web");

        assertThat(web.version()).isEqualTo("2.7.18");
        assertThat(web.versionSource()).isEqualTo(VersionSource.PARENT);
        assertThat(web.declaredAt().map(Location::line)).contains(OptionalInt.of(10));
    }

    @Test
    void load_boot2Legacy_explicitVersionsPointAtTheirOwnLines() {
        assertThat(dependency(boot2Legacy, "", "org.apache.commons:commons-text"))
                .extracting(ResolvedDependency::version, d -> d.declaredAt().orElseThrow().line())
                .containsExactly("1.9", OptionalInt.of(40));
        assertThat(dependency(boot2Legacy, "", "com.google.guava:guava").version())
                .isEqualTo("30.1-jre");
    }

    @Test
    void load_boot2Legacy_extractsSpringBootAndJavaVersions() {
        MavenModule module = boot2Legacy.module("").orElseThrow();

        assertThat(module.springBootVersion())
                .hasValueSatisfying(
                        boot -> {
                            assertThat(boot.value()).isEqualTo("2.7.18");
                            assertThat(boot.declaredAt().map(Location::line))
                                    .contains(OptionalInt.of(10));
                        });
        assertThat(module.javaVersion())
                .hasValueSatisfying(
                        java -> {
                            assertThat(java.value()).isEqualTo("11");
                            assertThat(java.declaredAt().map(Location::line))
                                    .contains(OptionalInt.of(19));
                        });
    }

    @Test
    void load_boot2Legacy_treeShowsTransitiveDependenciesWithTheirPath() {
        List<String> paths = paths(boot2Legacy.module("").orElseThrow());

        assertThat(paths)
                .anyMatch(
                        p ->
                                p.startsWith("org.springframework.boot:spring-boot-starter-web > ")
                                        && p.endsWith(
                                                " > com.fasterxml.jackson.core:jackson-databind"));
        assertThat(boot2Legacy.warnings()).isEmpty();
    }

    @Test
    void load_offlineWithEmptyLocalRepository_keepsModulesAndReportsWarnings(
            @TempDir Path emptyRepository) {
        MavenModelLoader loader =
                new MavenModelLoader(
                        new MavenResolverSettings(
                                emptyRepository, SeededMavenRepository.NO_REMOTE, true));

        MavenProjectModel model = loader.load(Fixture.BOOT2_LEGACY.path());

        assertThat(model.modules())
                .singleElement()
                .satisfies(
                        module -> {
                            assertThat(module.artifactId()).isEqualTo("boot2-legacy");
                            assertThat(module.dependencies()).isEmpty();
                        });
        assertThat(model.warnings()).anyMatch(w -> w.contains("spring-boot-starter-parent"));
    }

    @Test
    void load_repositoryWithoutPom_returnsEmptyModel(@TempDir Path repository) {
        MavenModelLoader loader = new MavenModelLoader(MavenResolverSettings.defaults());

        assertThat(loader.load(repository).isEmpty()).isTrue();
    }

    @Test
    void load_parentOnRemoteRepository_downloadsItAndPointsAtTheParentVersion(
            WireMockRuntimeInfo wireMock, @TempDir Path localRepository, @TempDir Path repository)
            throws IOException {
        stubFor(
                get(urlEqualTo("/maven2/com/example/remote-parent/1.0/remote-parent-1.0.pom"))
                        .willReturn(aResponse().withBody(remoteParentPom())));
        Files.writeString(repository.resolve("pom.xml"), childPom());
        MavenModelLoader loader =
                new MavenModelLoader(
                        new MavenResolverSettings(
                                localRepository,
                                URI.create(wireMock.getHttpBaseUrl() + "/maven2/"),
                                false));

        MavenProjectModel model = loader.load(repository);

        ResolvedDependency lang3 = dependency(model, "", "org.apache.commons:commons-lang3");
        assertThat(lang3.version()).isEqualTo("3.14.0");
        assertThat(lang3.versionSource()).isEqualTo(VersionSource.PARENT);
        assertThat(lang3.declaredAt().map(Location::line)).contains(OptionalInt.of(6));
    }

    private static ResolvedDependency dependency(
            MavenProjectModel model, String module, String dependency) {
        String[] coordinates = dependency.split(":");
        return model.module(module)
                .flatMap(m -> m.dependency(coordinates[0], coordinates[1]))
                .orElseThrow(() -> new AssertionError(dependency + " not in module " + module));
    }

    /** Every path in the module's tree, as {@code a > b > c} of {@code groupId:artifactId}. */
    private static List<String> paths(MavenModule module) {
        List<String> paths = new ArrayList<>();
        for (DependencyNode direct : module.dependencyTree()) {
            direct.walk(
                    path ->
                            paths.add(
                                    String.join(
                                            " > ",
                                            path.stream().map(DependencyNode::key).toList())));
        }
        return paths;
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static String remoteParentPom() {
        return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>remote-parent</artifactId>
          <version>1.0</version>
          <packaging>pom</packaging>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.apache.commons</groupId>
                <artifactId>commons-lang3</artifactId>
                <version>3.14.0</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """;
    }

    private static String childPom() {
        return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>remote-parent</artifactId>
            <version>1.0</version>
            <relativePath/>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.apache.commons</groupId>
              <artifactId>commons-lang3</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;
    }
}
