package dev.stealth.core;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The JSON Schemas published under {@code docs/schema/}, for validating output in tests. */
public final class Schemas {

    private Schemas() {}

    /** Validation errors for {@code document} (JSON or YAML) against {@code docs/schema/<name>}. */
    public static List<String> validate(String name, String document, InputFormat format) {
        try (InputStream schema = Files.newInputStream(docs().resolve("schema").resolve(name))) {
            Schema compiled =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(schema, InputFormat.JSON);
            return compiled.validate(document, format).stream().map(Error::toString).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The repository's {@code docs/}, found by walking up from the working directory. */
    public static Path docs() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/schema");
            if (Files.isDirectory(candidate)) {
                return candidate.getParent();
            }
        }
        throw new IllegalStateException(
                "docs/schema not found above " + Path.of("").toAbsolutePath());
    }
}
