package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code docs/schema/stealth-yml.schema.json} agrees with what the loader accepts. */
class StealthYmlSchemaTest {

    private static final String SCHEMA = "stealth-yml.schema.json";

    @Test
    void schema_fullExample_isValid() {
        assertThat(Schemas.validate(SCHEMA, StealthConfigLoaderTest.FULL_EXAMPLE, InputFormat.YAML))
                .isEmpty();
    }

    @Test
    void schema_failUnderAsANumber_isValid() {
        assertThat(Schemas.validate(SCHEMA, "version: 1\nfail-under: 70\n", InputFormat.YAML))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "version: 1\nfail-under: 120\n",
                "version: 1\nseverity:\n  deps/outdated-major: urgent\n",
                "version: 1\nignroe: []\n",
                "version: 2\n",
                "version: 1\nallow:\n  dependencies:\n    - reason: x\n",
                "version: 1\nanalyzers:\n  maintenance:\n    stale-after: 2y\n",
            })
    void schema_invalidFile_isRejectedLikeTheLoaderRejectsOrWarns(String yaml) {
        assertThat(Schemas.validate(SCHEMA, yaml, InputFormat.YAML)).isNotEmpty();
    }
}
