package dev.stealth.core.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * GETs with a disk cache, retries and an offline mode, shared by every client of a public API
 * (Maven Central, OSV.dev, endoflife.date).
 *
 * <ul>
 *   <li>A fresh cache entry is returned without a request.
 *   <li>429 and 5xx responses are retried with exponential backoff, honouring {@code Retry-After}
 *       up to a limit.
 *   <li>If the request still fails, a stale cache entry is returned rather than failing.
 *   <li>Offline, only the cache is used, however old.
 * </ul>
 */
public class CachedHttpClient {

    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

    private final HttpClient http;
    private final HttpCache cache;
    private final BooleanSupplier offline;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public CachedHttpClient(
            HttpClient http,
            HttpCache cache,
            BooleanSupplier offline,
            int maxAttempts,
            Duration initialBackoff) {
        this.http = Objects.requireNonNull(http, "http");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.offline = Objects.requireNonNull(offline, "offline");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
    }

    /** Three attempts, starting with a 500 ms backoff. */
    public static CachedHttpClient create(HttpCache cache, BooleanSupplier offline) {
        HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
        return new CachedHttpClient(http, cache, offline, 3, Duration.ofMillis(500));
    }

    /**
     * The body at {@code uri}, or empty if the server says it doesn't exist (404 or 410).
     *
     * @throws IOException if there's no usable response and nothing cached, including when offline
     */
    public Optional<String> get(URI uri) throws IOException, InterruptedException {
        Optional<HttpCache.Entry> cached = cache.get(uri);
        if (cached.isPresent() && (cached.get().fresh() || offline.getAsBoolean())) {
            return cached.get().body();
        }
        if (offline.getAsBoolean()) {
            throw new IOException("offline and not cached: " + uri);
        }
        try {
            Optional<String> body = fetch(uri);
            cache.put(uri, body);
            return body;
        } catch (IOException e) {
            if (cached.isPresent()) {
                return cached.get().body();
            }
            throw e;
        }
    }

    private Optional<String> fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header(
                                "User-Agent",
                                "stealth (+https://github.com/Will-Barnard-WB/stealth)")
                        .GET()
                        .build();
        Duration backoff = initialBackoff;
        IOException failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait = backoff;
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return Optional.of(response.body());
                }
                if (status == 404 || status == 410) {
                    return Optional.empty();
                }
                if (status != 429 && status < 500) {
                    throw new NotRetryableException("HTTP " + status + " from " + uri);
                }
                failure = new IOException("HTTP " + status + " from " + uri);
                wait = retryAfter(response).orElse(backoff);
            } catch (NotRetryableException e) {
                throw e;
            } catch (IOException e) {
                failure = e;
            }
            if (attempt < maxAttempts) {
                Thread.sleep(wait.toMillis());
                backoff = backoff.multipliedBy(2);
            }
        }
        throw failure;
    }

    private static final class NotRetryableException extends IOException {
        NotRetryableException(String message) {
            super(message);
        }
    }

    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        OptionalLong seconds =
                response.headers()
                        .firstValue("Retry-After")
                        .filter(value -> value.matches("\\d+"))
                        .map(value -> OptionalLong.of(Long.parseLong(value)))
                        .orElse(OptionalLong.empty());
        if (seconds.isEmpty()) {
            return Optional.empty();
        }
        Duration wait = Duration.ofSeconds(seconds.getAsLong());
        return Optional.of(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
    }
}
