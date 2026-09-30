package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PathGlobTest {

    @ParameterizedTest
    @CsvSource({
        "fixtures/**, fixtures/with-secrets/pom.xml, true",
        "fixtures/**, src/fixtures/a.txt, false",
        "fixtures/, fixtures/a/b.txt, true",
        "fixtures, src/fixtures/a.txt, true",
        "**/generated/**, a/b/generated/X.java, true",
        "**/generated/**, generated/X.java, true",
        "**/generated/**, a/generatedX/X.java, false",
        "*.pem, deploy-key.pem, true",
        "*.pem, src/main/resources/deploy-key.pem, true",
        "*.pem, deploy-key.pem.bak, false",
        "src/*.pem, src/main/key.pem, false",
        "src/*.pem, src/key.pem, true",
        "/pom.xml, pom.xml, true",
        "/pom.xml, api/pom.xml, false",
        "src/test/resources/fake-credentials.properties,"
                + " src/test/resources/fake-credentials.properties, true",
        "key?.pem, key1.pem, true",
        "key?.pem, key/.pem, false",
        "a.b, aXb, false"
    })
    void matches_gitignoreStyle(String glob, String path, boolean expected) {
        assertThat(PathGlob.of(glob).matches(path)).isEqualTo(expected);
    }

    @Test
    void of_blank_isRejected() {
        assertThatThrownBy(() -> PathGlob.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
