package dev.stealth.core.git;

import dev.stealth.core.RepoContext;
import dev.stealth.core.SharedResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;

/**
 * The files in the repo's working tree that git tracks or would track: tracked files, plus
 * untracked ones no {@code .gitignore} excludes. Outside a git repository, every file under the
 * root. Either way {@code .git/} and {@code target/} directories are skipped (ADR-0004), as are
 * symlinks and submodules.
 *
 * <p>Paths are relative to {@link RepoContext#root()}, with forward slashes, sorted. The root may
 * be a subdirectory of a work tree, such as one module of a larger repo.
 */
public final class WorkingTreeFiles {

    public static final SharedResource<List<String>> FILES =
            context -> {
                try {
                    return list(context.root());
                } catch (IOException e) {
                    throw new UncheckedIOException("Couldn't list files in " + context.root(), e);
                }
            };

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target");

    private WorkingTreeFiles() {}

    public static List<String> list(Path root) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            return walk(root);
        }
        try (Repository repository = builder.setMustExist(true).build()) {
            if (repository.isBare()) {
                return walk(root);
            }
            return tracked(repository, root);
        }
    }

    private static List<String> tracked(Repository repository, Path root) throws IOException {
        Path workTree = repository.getWorkTree().toPath().toRealPath();
        String prefix = slashes(workTree.relativize(root.toRealPath()));
        List<String> files = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(new DirCacheIterator(repository.readDirCache()));
            walk.addTree(new FileTreeIterator(repository));
            walk.setRecursive(false);
            if (!prefix.isEmpty()) {
                walk.setFilter(PathFilter.create(prefix));
            }
            while (walk.next()) {
                WorkingTreeIterator file = walk.getTree(1, WorkingTreeIterator.class);
                if (file == null) {
                    continue; // deleted from the working tree
                }
                boolean tracked = walk.getTree(0, DirCacheIterator.class) != null;
                if (!tracked && file.isEntryIgnored()) {
                    continue;
                }
                if (walk.isSubtree()) {
                    if (!SKIPPED_DIRECTORIES.contains(walk.getNameString())) {
                        walk.enterSubtree();
                    }
                    continue;
                }
                FileMode mode = file.getEntryFileMode();
                String path = walk.getPathString();
                if ((mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE)
                        && (prefix.isEmpty() || path.startsWith(prefix + "/"))) {
                    files.add(prefix.isEmpty() ? path : path.substring(prefix.length() + 1));
                }
            }
        }
        files.sort(null);
        return List.copyOf(files);
    }

    private static List<String> walk(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path dir, BasicFileAttributes attributes) {
                        return !dir.equals(root)
                                        && SKIPPED_DIRECTORIES.contains(
                                                dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile()) {
                            files.add(slashes(root.relativize(file)));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        files.sort(null);
        return List.copyOf(files);
    }

    private static String slashes(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
