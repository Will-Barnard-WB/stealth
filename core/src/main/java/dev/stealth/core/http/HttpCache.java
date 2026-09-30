package dev.stealth.core.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Response bodies on disk, one file per URL, in {@code ~/.stealth/cache/http} by default. A file's
 * modification time is when it was fetched. "Not found" is cached too, so an artifact that isn't on
 * Maven Central isn't looked up on every run.
 */
public class HttpCache {

    private final Path directory;
    private final Duration timeToLive;
    private final Clock clock;

    public HttpCache(Path directory, Duration timeToLive, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code ~/.stealth/cache/http}, entries fresh for 24 hours. */
    public static HttpCache defaultCache() {
        return new HttpCache(
                Path.of(System.getProperty("user.home"), ".stealth", "cache", "http"),
                Duration.ofHours(24),
                Clock.systemUTC());
    }

    /**
     * A cached response.
     *
     * @param body the response body, or empty if the URL was not found
     * @param fresh whether it's younger than the time to live
     */
    public record Entry(Optional<String> body, boolean fresh) {}

    public Optional<Entry> get(URI uri) {
        Path file = file(uri);
        Path notFound = notFoundMarker(uri);
        try {
            if (Files.isRegularFile(file)) {
                return Optional.of(
                        new Entry(
                                Optional.of(Files.readString(file, StandardCharsets.UTF_8)),
                                isFresh(file)));
            }
            if (Files.isRegularFile(notFound)) {
                return Optional.of(new Entry(Optional.empty(), isFresh(notFound)));
            }
            return Optional.empty();
        } catch (IOException e) {
            // A corrupt or unreadable entry is a miss, not a failure
            return Optional.empty();
        }
    }

    /** Stores {@code body}, or "not found" if it's empty. */
    public void put(URI uri, Optional<String> body) {
        try {
            Files.createDirectories(directory);
            Path file = file(uri);
            Path notFound = notFoundMarker(uri);
            Path target = body.isPresent() ? file : notFound;
            // Write then move, so a concurrent reader never sees half a file
            Path temp = Files.createTempFile(directory, "entry", ".tmp");
            Files.writeString(temp, body.orElse(""), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(temp, FileTime.from(clock.instant()));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(body.isPresent() ? notFound : file);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't write the HTTP cache in " + directory, e);
        }
    }

    private boolean isFresh(Path file) throws IOException {
        Instant fetched = Files.getLastModifiedTime(file).toInstant();
        return fetched.plus(timeToLive).isAfter(clock.instant());
    }

    private Path file(URI uri) {
        return directory.resolve(key(uri) + ".body");
    }

    private Path notFoundMarker(URI uri) {
        return directory.resolve(key(uri) + ".404");
    }

    private static String key(URI uri) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(sha256.digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
