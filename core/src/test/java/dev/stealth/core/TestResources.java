package dev.stealth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

/**
 * A test resource directory as a {@link Path}, whether it's on disk ({@code target/test-classes})
 * or inside core's test jar, which other modules' tests read it from.
 */
public final class TestResources {

    private TestResources() {}

    public static Path path(String resource) {
        URL url = TestResources.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("No test resource " + resource);
        }
        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try {
                    FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException e) {
                    // Opened by an earlier call
                }
            }
            return Path.of(uri);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
