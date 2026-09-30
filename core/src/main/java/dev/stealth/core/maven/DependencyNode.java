package dev.stealth.core.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** A dependency in a module's resolved tree, with the dependencies it brings in. */
public record DependencyNode(
        String groupId,
        String artifactId,
        String version,
        String scope,
        List<DependencyNode> children) {

    public DependencyNode {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(scope, "scope");
        children = List.copyOf(children);
    }

    /** {@code groupId:artifactId}. */
    public String key() {
        return groupId + ":" + artifactId;
    }

    /**
     * Visits this node and everything below it, depth-first. {@code path} runs from the direct
     * dependency down to the visited node, which is last.
     */
    public void walk(Consumer<List<DependencyNode>> visitor) {
        walk(List.of(this), visitor);
    }

    private static void walk(List<DependencyNode> path, Consumer<List<DependencyNode>> visitor) {
        visitor.accept(path);
        for (DependencyNode child : path.getLast().children()) {
            List<DependencyNode> childPath = new ArrayList<>(path);
            childPath.add(child);
            walk(List.copyOf(childPath), visitor);
        }
    }
}
