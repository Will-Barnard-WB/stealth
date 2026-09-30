package dev.stealth.core.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Finds parent POMs and imported BOMs for the model builder: this repository's own modules first,
 * then the local repository and the configured remote repository. Repositories declared inside POMs
 * are ignored, so a run only ever talks to the configured repository.
 */
final class ResolverModelResolver implements ModelResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final Map<String, Path> modules;

    ResolverModelResolver(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repositories,
            Map<String, Path> modules) {
        this.system = system;
        this.session = session;
        this.repositories = repositories;
        this.modules = modules;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Path module = modules.get(groupId + ":" + artifactId + ":" + version);
        if (module != null) {
            return new FileModelSource(module.toFile());
        }
        try {
            var request =
                    new ArtifactRequest(
                            new DefaultArtifact(groupId, artifactId, "", "pom", version),
                            repositories,
                            null);
            return new FileModelSource(
                    system.resolveArtifact(session, request).getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(
                dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) {
        // Ignored on purpose: see the class comment
    }

    @Override
    public void addRepository(Repository repository, boolean replace) {
        // Ignored on purpose: see the class comment
    }

    @Override
    public ModelResolver newCopy() {
        return this;
    }
}
