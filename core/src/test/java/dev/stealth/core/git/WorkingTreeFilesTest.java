package dev.stealth.core.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingTreeFilesTest {

    @TempDir private Path repo;

    @Test
    void list_gitRepo_includesTrackedAndUntrackedButNotIgnoredFiles() throws Exception {
        write("src/App.java", "class App {}");
        write("forced.log", "tracked before it was ignored");
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern("src").addFilepattern("forced.log").call();
            write(".gitignore", "*.log\n/local/\n");
            git.add().addFilepattern(".gitignore").call();
        }
        write("debug.log", "ignored");
        write("local/secrets.env", "ignored directory");
        write("notes.txt", "untracked, not ignored");

        assertThat(WorkingTreeFiles.list(repo))
                .containsExactly(".gitignore", "forced.log", "notes.txt", "src/App.java");
    }

    @Test
    void list_gitRepo_skipsTargetEvenWhenNotIgnored() throws Exception {
        write("pom.xml", "<project/>");
        write("target/classes/app.properties", "built");
        write("api/target/x.txt", "built");
        Git.init().setDirectory(repo.toFile()).call().close();

        assertThat(WorkingTreeFiles.list(repo)).containsExactly("pom.xml");
    }

    @Test
    void list_subdirectoryOfAWorkTree_isRelativeToThatSubdirectory() throws Exception {
        write(".gitignore", "*.tmp\n");
        write("module/pom.xml", "<project/>");
        write("module/src/a.tmp", "ignored");
        write("other/pom.xml", "<project/>");
        Git.init().setDirectory(repo.toFile()).call().close();

        assertThat(WorkingTreeFiles.list(repo.resolve("module"))).containsExactly("pom.xml");
    }

    @Test
    void list_notAGitRepo_walksEverythingExceptGitAndTarget() throws Exception {
        write("pom.xml", "<project/>");
        write("src/a.txt", "a");
        write("target/b.txt", "b");
        write(".git/config", "not really a repo, no HEAD");

        assertThat(WorkingTreeFiles.list(repo)).containsExactly("pom.xml", "src/a.txt");
    }

    private void write(String path, String content) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
