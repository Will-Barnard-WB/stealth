package dev.stealth.core.maven;

import dev.stealth.core.Fixture;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A local Maven repository holding the POMs that the fixtures' parents, BOMs and dependency trees
 * need, so {@link MavenModelLoader} tests run offline. Stored as {@code maven-repo.zip} in the test
 * resources.
 *
 * <p>To regenerate it after changing a fixture's dependencies, run {@link #main} from the
 * repository root. It loads the fixtures against Maven Central into an empty repository and zips
 * the POMs.
 */
public final class SeededMavenRepository {

    static final String RESOURCE = "/maven-repo.zip";

    /** Unreachable, so a test that isn't offline fails fast instead of reaching Maven Central. */
    public static final URI NO_REMOTE = URI.create("http://127.0.0.1:9/maven2/");

    private static final List<Fixture> FIXTURES =
            List.of(
                    Fixture.MULTI_MODULE,
                    Fixture.BOOT2_LEGACY,
                    Fixture.BOOT4_CLEAN,
                    Fixture.EOL_RUNTIME);

    private SeededMavenRepository() {}

    /** Unzips the seeded repository into {@code directory} and returns offline settings for it. */
    public static MavenResolverSettings extractTo(Path directory) {
        try (InputStream in = SeededMavenRepository.class.getResourceAsStream(RESOURCE);
                ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException("zip entry outside the target: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new MavenResolverSettings(directory, NO_REMOTE, true);
    }

    public static void main(String[] args) throws IOException {
        Path repository = Files.createTempDirectory("stealth-seed");
        MavenModelLoader loader =
                new MavenModelLoader(
                        new MavenResolverSettings(
                                repository, MavenResolverSettings.MAVEN_CENTRAL, false));
        for (Fixture fixture : FIXTURES) {
            MavenProjectModel model = loader.load(fixture.path());
            if (!model.warnings().isEmpty()) {
                throw new IllegalStateException(fixture + ": " + model.warnings());
            }
        }

        Path zipFile = Path.of("core/src/test/resources" + RESOURCE);
        Files.createDirectories(zipFile.getParent());
        try (OutputStream out = Files.newOutputStream(zipFile);
                ZipOutputStream zip = new ZipOutputStream(out);
                Stream<Path> files = Files.walk(repository)) {
            for (Path pom : files.filter(f -> f.toString().endsWith(".pom")).sorted().toList()) {
                String name = repository.relativize(pom).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0); // reproducible zip
                zip.putNextEntry(entry);
                Files.copy(pom, zip);
                zip.closeEntry();
            }
        }
        System.out.println("Wrote " + zipFile);
    }
}
