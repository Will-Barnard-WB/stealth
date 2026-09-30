package dev.stealth.core.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

/**
 * Lets the resolver read this repository's own modules from disk while collecting dependency trees,
 * so internal modules are never looked up remotely.
 */
final class ReactorReader implements WorkspaceReader {

    private final WorkspaceRepository repository = new WorkspaceRepository("stealth-reactor");
    private final Map<String, Path> modules;

    ReactorReader(Map<String, Path> modules) {
        this.modules = modules;
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        if (!"pom".equals(artifact.getExtension())) {
            return null;
        }
        Path pom = modules.get(coordinates(artifact));
        return pom == null ? null : pom.toFile();
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        return modules.containsKey(coordinates(artifact))
                ? List.of(artifact.getBaseVersion())
                : List.of();
    }

    private static String coordinates(Artifact artifact) {
        return artifact.getGroupId()
                + ":"
                + artifact.getArtifactId()
                + ":"
                + artifact.getBaseVersion();
    }
}
