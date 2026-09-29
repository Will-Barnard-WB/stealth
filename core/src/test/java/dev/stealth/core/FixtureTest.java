package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FixtureTest {

    @TempDir private Path tempDir;

    @ParameterizedTest
    @EnumSource(Fixture.class)
    void path_everyFixture_isMavenProject(Fixture fixture) {
        assertThat(fixture.path().resolve("pom.xml")).isRegularFile();
        assertThat(fixture.context().root()).isEqualTo(fixture.path());
    }

    @Test
    void copyTo_multiModule_copiesAllModules() {
        Path copy = Fixture.MULTI_MODULE.copyTo(tempDir);

        assertThat(copy).isEqualTo(tempDir.resolve("multi-module"));
        assertThat(copy.resolve("pom.xml"))
                .hasSameTextualContentAs(Fixture.MULTI_MODULE.path().resolve("pom.xml"));
        assertThat(copy.resolve("service/pom.xml")).isRegularFile();
        assertThat(copy.resolve("app/src/main/java/com/example/multi/app/Main.java"))
                .isRegularFile();
    }

    @Test
    void copyTo_modifiedCopy_leavesFixtureUnchanged() throws IOException {
        Path original = Fixture.BOOT4_CLEAN.path().resolve("pom.xml");
        String before = Files.readString(original);

        Path copy = Fixture.BOOT4_CLEAN.copyTo(tempDir);
        Files.writeString(copy.resolve("pom.xml"), "changed");

        assertThat(original).hasContent(before);
    }

    @Test
    void copyTree_sourceWithBuildOutput_skipsTargetDirectory() throws IOException {
        Path source = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source.resolve("module/target/classes"));
        Files.writeString(source.resolve("module/target/classes/Built.class"), "");
        Files.createDirectories(source.resolve("module/src"));
        Files.writeString(source.resolve("module/src/Main.java"), "class Main {}");

        Path copy = tempDir.resolve("copy");
        Fixture.copyTree(source, copy);

        assertThat(copy.resolve("pom.xml")).hasContent("<project/>");
        assertThat(copy.resolve("module/src/Main.java")).isRegularFile();
        assertThat(copy.resolve("module/target")).doesNotExist();
    }
}
