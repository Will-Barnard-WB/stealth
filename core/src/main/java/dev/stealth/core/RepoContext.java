package dev.stealth.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** The repository being analyzed, shared by every analyzer in a run. Thread-safe. */
public final class RepoContext {

    private final Path root;
    private final StealthConfig config;
    private final ConcurrentMap<SharedResource<?>, Memoized<?>> resources =
            new ConcurrentHashMap<>();

    public RepoContext(Path root, StealthConfig config) {
        this.root = Objects.requireNonNull(root, "root");
        this.config = Objects.requireNonNull(config, "config");
    }

    public Path root() {
        return root;
    }

    public StealthConfig config() {
        return config;
    }

    /**
     * Returns the shared resource, loading it on first use. Concurrent callers wait for that one
     * load; if it throws, every caller gets the same exception rather than loading again.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(SharedResource<T> resource) {
        Objects.requireNonNull(resource, "resource");
        Memoized<T> memoized =
                (Memoized<T>) resources.computeIfAbsent(resource, key -> new Memoized<>());
        return memoized.get(resource, this);
    }

    /**
     * Loads outside the map's lock so one slow resource doesn't block the others. Uses a {@link
     * ReentrantLock} because {@code synchronized} pins virtual threads on Java 21.
     */
    private static final class Memoized<T> {

        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private T value;
        private RuntimeException failure;

        T get(SharedResource<T> resource, RepoContext context) {
            if (!loaded) {
                lock.lock();
                try {
                    if (!loaded) {
                        try {
                            value = resource.load(context);
                        } catch (RuntimeException e) {
                            failure = e;
                        }
                        loaded = true;
                    }
                } finally {
                    lock.unlock();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
