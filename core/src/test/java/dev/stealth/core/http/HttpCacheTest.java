package dev.stealth.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpCacheTest {

    @TempDir private Path directory;

    // What the deps and maintenance analyzers do in parallel: fetch, then cache, the same URL.
    // On Windows this used to fail with "Couldn't write the HTTP cache".
    @Test
    void put_sameKeyFromManyThreadsWhileReading_neverFailsAndLeavesAReadableEntry()
            throws Exception {
        HttpCache cache = new HttpCache(directory, Duration.ofHours(24), Clock.systemUTC());
        String key = "https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml";
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    for (int i = 0; i < 50; i++) {
                                        cache.put(key, Optional.of("<metadata/>"));
                                        cache.get(key);
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(cache.get(key))
                .hasValueSatisfying(e -> assertThat(e.body()).contains("<metadata/>"));
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void put_unwritableDirectory_returnsFalseInsteadOfFailing() throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("file"), "");
        HttpCache cache = new HttpCache(notADirectory, Duration.ofHours(24), Clock.systemUTC());

        assertThat(cache.put("key", Optional.of("body"))).isFalse();
        assertThat(cache.get("key")).isEmpty();
    }
}
