package dev.stealth.core;

/**
 * Something expensive that several analyzers need, such as the Maven project model or the JGit
 * {@code Repository}. {@link RepoContext#get} loads each one at most once per run. Declare
 * instances as constants, since the instance is the cache key.
 */
@FunctionalInterface
public interface SharedResource<T> {

    T load(RepoContext context);
}
