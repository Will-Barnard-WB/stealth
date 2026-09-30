package dev.stealth.core.maven;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Maven build of the repository being analyzed: every module's effective dependencies, built
 * once per run by {@link MavenModelLoader}. Empty for a repository without a root {@code pom.xml}.
 *
 * @param modules the root module first, then its modules in {@code <modules>} order, depth-first
 * @param warnings what couldn't be resolved (missing parents or BOMs, unreachable repositories);
 *     affected modules are still listed, with whatever could be read
 */
public record MavenProjectModel(List<MavenModule> modules, List<String> warnings) {

    public MavenProjectModel {
        modules = List.copyOf(modules);
        warnings = List.copyOf(warnings);
    }

    public static MavenProjectModel empty() {
        return new MavenProjectModel(List.of(), List.of());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** The module in {@code directory}, repo-relative ({@code ""} for the root). */
    public Optional<MavenModule> module(String directory) {
        Objects.requireNonNull(directory, "directory");
        return modules.stream().filter(module -> module.directory().equals(directory)).findFirst();
    }
}
