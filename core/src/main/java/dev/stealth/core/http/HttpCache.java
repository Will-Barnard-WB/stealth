package dev.stealth.core.http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
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

    private static final int MOVE_ATTEMPTS = 5;

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
        return get(uri.toString());
    }

    /** {@link #get(URI)} for a request identified by more than its URL, such as a POST. */
    public Optional<Entry> get(String key) {
        Path file = file(key);
        Path notFound = notFoundMarker(key);
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
        put(uri.toString(), body);
    }

    /**
     * Stores {@code body}, or "not found" if it's empty. Best effort: a cache that can't be written
     * (a read-only home directory, or on Windows another thread replacing the same entry) never
     * fails the lookup that fetched the body.
     *
     * @return whether the entry was written
     */
    public boolean put(String key, Optional<String> body) {
        Path temp = null;
        try {
            Files.createDirectories(directory);
            Path file = file(key);
            Path notFound = notFoundMarker(key);
            Path target = body.isPresent() ? file : notFound;
            // Write then move, so a concurrent reader never sees half a file
            temp = Files.createTempFile(directory, "entry", ".tmp");
            Files.writeString(temp, body.orElse(""), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(temp, FileTime.from(clock.instant()));
            moveReplacing(temp, target);
            temp = null;
            Files.deleteIfExists(body.isPresent() ? notFound : file);
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Left for the next run's writes to ignore; it's never read
                }
            }
        }
    }

    /**
     * Windows refuses to replace a file another thread has open or is replacing at that moment (the
     * deps and maintenance analyzers fetch the same metadata in parallel), and lets go within
     * milliseconds, so retry briefly.
     */
    private static void moveReplacing(Path source, Path target)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException e) {
                if (attempt == MOVE_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(10L * attempt);
            }
        }
    }

    private boolean isFresh(Path file) throws IOException {
        Instant fetched = Files.getLastModifiedTime(file).toInstant();
        return fetched.plus(timeToLive).isAfter(clock.instant());
    }

    private Path file(String key) {
        return directory.resolve(hash(key) + ".body");
    }

    private Path notFoundMarker(String key) {
        return directory.resolve(hash(key) + ".404");
    }

    static String hash(String key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
