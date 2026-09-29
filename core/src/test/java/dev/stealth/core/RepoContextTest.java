package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RepoContextTest {

    private final RepoContext context = new RepoContext(Path.of("repo"), StealthConfig.defaults());

    @Test
    void get_calledConcurrently_loadsOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        SharedResource<String> model =
                ctx -> {
                    loads.incrementAndGet();
                    awaitUninterruptibly(release);
                    return "model";
                };

        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> context.get(model)));
            }
            release.countDown();
        }

        for (Future<String> result : results) {
            assertThat(result.get()).isEqualTo("model");
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void get_loadFails_rethrowsSameExceptionWithoutReloading() {
        AtomicInteger loads = new AtomicInteger();
        SharedResource<String> model =
                ctx -> {
                    loads.incrementAndGet();
                    throw new IllegalStateException("pom.xml is malformed");
                };

        assertThatThrownBy(() -> context.get(model)).hasMessage("pom.xml is malformed");
        assertThatThrownBy(() -> context.get(model)).hasMessage("pom.xml is malformed");
        assertThat(loads).hasValue(1);
    }

    @Test
    void get_differentResources_loadsEach() {
        SharedResource<String> first = ctx -> "first";
        SharedResource<String> second = ctx -> "second";

        assertThat(context.get(first)).isEqualTo("first");
        assertThat(context.get(second)).isEqualTo("second");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
