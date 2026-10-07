package dev.stealth.core.clean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PomEditorTest {

    private static final String POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <!-- the app -->
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.18</version>
                </parent>
                <artifactId>app</artifactId>

                <properties>
                    <java.version>11</java.version>
                    <guava.version>30.1-jre</guava.version>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-text</artifactId>
                        <version>1.9</version>
                    </dependency>
                </dependencies>

                <profiles>
                    <profile>
                        <properties>
                            <tomcat.version>1.0</tomcat.version>
                        </properties>
                    </profile>
                </profiles>
            </project>
            """;

    @Test
    void setVersion_onlyChangesTheVersionOnThatLine() throws IOException {
        String edited =
                PomEditor.apply(POM, new PomEdit.SetVersion("pom.xml", 20, "1.9", "1.10.0"));

        assertThat(edited)
                .isEqualTo(POM.replace("<version>1.9</version>", "<version>1.10.0</version>"));
    }

    @Test
    void setVersion_versionNotOnTheLine_failsInsteadOfGuessing() {
        assertThatThrownBy(
                        () ->
                                PomEditor.apply(
                                        POM,
                                        new PomEdit.SetVersion("pom.xml", 19, "1.9", "1.10.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("doesn't set version 1.9 exactly once");
    }

    @Test
    void setProperty_existing_changesItsValueOnly() throws IOException {
        String edited =
                PomEditor.apply(
                        POM, new PomEdit.SetProperty("pom.xml", "guava.version", "33.7.2-jre"));

        assertThat(edited).isEqualTo(POM.replace("30.1-jre", "33.7.2-jre"));
    }

    @Test
    void setProperty_new_isAddedAtTheEndOfTheTopLevelPropertiesNotAProfiles() throws IOException {
        String edited =
                PomEditor.apply(
                        POM, new PomEdit.SetProperty("pom.xml", "tomcat.version", "9.0.98"));

        assertThat(edited)
                .isEqualTo(
                        POM.replace(
                                "        <guava.version>30.1-jre</guava.version>\n",
                                "        <guava.version>30.1-jre</guava.version>\n"
                                        + "        <tomcat.version>9.0.98</tomcat.version>\n"))
                .contains("<tomcat.version>1.0</tomcat.version>");
    }

    @Test
    void setProperty_noPropertiesSection_addsOneBeforeTheDependencies() throws IOException {
        String pom = POM.replaceAll("(?s)    <properties>.*?</properties>\n\n", "");

        String edited =
                PomEditor.apply(
                        pom, new PomEdit.SetProperty("pom.xml", "tomcat.version", "9.0.98"));

        assertThat(edited)
                .contains(
                        """
                            <properties>
                                <tomcat.version>9.0.98</tomcat.version>
                            </properties>
                            <dependencies>
                        """);
    }

    @Test
    void pinVersion_noDependencyManagement_addsTheSectionBeforeTheDependencies()
            throws IOException {
        String edited =
                PomEditor.apply(
                        POM, new PomEdit.PinVersion("pom.xml", "org.yaml", "snakeyaml", "1.33"));

        assertThat(edited)
                .contains(
                        """
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.yaml</groupId>
                                        <artifactId>snakeyaml</artifactId>
                                        <version>1.33</version>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <dependencies>
                        """);
    }

    @Test
    void pinVersion_existingDependencyManagement_addsTheEntryToIt() throws IOException {
        String pom =
                """
                <project>
                    <artifactId>app</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>bom</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;

        String edited =
                PomEditor.apply(
                        pom, new PomEdit.PinVersion("pom.xml", "org.yaml", "snakeyaml", "1.33"));

        assertThat(edited)
                .isEqualTo(
                        pom.replace(
                                "                <scope>import</scope>\n"
                                        + "            </dependency>\n",
                                "                <scope>import</scope>\n            </dependency>\n"
                                        + "            <dependency>\n"
                                        + "                <groupId>org.yaml</groupId>\n"
                                        + "                <artifactId>snakeyaml</artifactId>\n"
                                        + "                <version>1.33</version>\n"
                                        + "            </dependency>\n"));
    }

    @Test
    void apply_windowsLineEndings_arePreserved() throws IOException {
        String pom = POM.replace("\n", "\r\n");

        String edited =
                PomEditor.apply(
                        pom, new PomEdit.SetProperty("pom.xml", "tomcat.version", "9.0.98"));

        assertThat(edited)
                .doesNotContainPattern("[^\r]\n")
                .contains("<tomcat.version>9.0.98</tomcat.version>\r\n");
    }

    @Test
    void apply_notAPom_fails() {
        assertThatThrownBy(
                        () ->
                                PomEditor.apply(
                                        "<project><oops></project>",
                                        new PomEdit.SetProperty("pom.xml", "x", "1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a well-formed POM");
    }
}
