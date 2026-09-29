package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class FindingTest {

    @Test
    void new_ruleIdWithoutAnalyzerPrefix_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AnalyzerRunnerTest.finding("outdated"))
                .withMessageContaining("<analyzer>/<rule>");
    }

    @Test
    void new_ruleIdNotKebabCase_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AnalyzerRunnerTest.finding("deps/Outdated_Dependency"));
    }

    @Test
    void location_file_setsPathAndLine() {
        Location location = Location.file("src/main/resources/application.yml", 12);

        assertThat(location.path()).contains("src/main/resources/application.yml");
        assertThat(location.line()).hasValue(12);
        assertThat(location.module()).isEmpty();
    }

    @Test
    void location_absoluteOrBackslashPath_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> Location.file("/home/me/pom.xml", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> Location.file("C:/repo/pom.xml", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> Location.file("core\\pom.xml", 1));
    }

    @Test
    void location_lineWithoutPath_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Location(Optional.empty(), OptionalInt.of(3), Optional.empty()));
    }
}
