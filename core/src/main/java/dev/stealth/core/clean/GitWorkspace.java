package dev.stealth.core.clean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The git operations {@code stealth clean} needs, through the git CLI so the user's own
 * configuration (identity, signing, hooks) applies. Changes are only ever made in a separate
 * worktree on a new branch: the user's checkout is never touched.
 */
final class GitWorkspace {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** The repository's top-level directory. */
    final Path top;

    private GitWorkspace(Path top) {
        this.top = top;
    }

    /** The git repository containing {@code directory}. */
    static GitWorkspace of(Path directory) throws CleanException, InterruptedException {
        Command.Result result = git(directory, "rev-parse", "--show-toplevel");
        if (!result.ok()) {
            throw new CleanException(
                    directory
                            + " isn't in a git repository; stealth clean works on a new branch,"
                            + " so it needs git");
        }
        return new GitWorkspace(Path.of(result.output().strip()).toAbsolutePath().normalize());
    }

    /** Refuses when there are uncommitted changes, which the new branch wouldn't include. */
    void requireClean() throws CleanException, InterruptedException {
        Command.Result status = git(top, "status", "--porcelain", "--untracked-files=no");
        if (!status.ok()) {
            throw new CleanException("git status failed: " + status.tail(5));
        }
        if (!status.output().isBlank()) {
            throw new CleanException(
                    "the working tree has uncommitted changes; commit or stash them first, so the"
                            + " cleanup branch starts from a known state");
        }
    }

    String head() throws CleanException, InterruptedException {
        return checked(git(top, "rev-parse", "HEAD"), "find HEAD").strip();
    }

    /** A new branch from HEAD, checked out in a temporary worktree. */
    Path addWorktree(String branch) throws CleanException, InterruptedException {
        Path directory;
        try {
            directory = Files.createTempDirectory("stealth-clean-");
            Files.delete(directory);
        } catch (IOException e) {
            throw new CleanException("can't create a temporary directory: " + e.getMessage(), e);
        }
        checked(
                git(top, "worktree", "add", "-b", branch, directory.toString(), "HEAD"),
                "create branch " + branch);
        return directory;
    }

    /** {@code ref} checked out, detached, in a temporary worktree: for comparing against it. */
    Path addDetachedWorktree(String ref) throws CleanException, InterruptedException {
        Path directory;
        try {
            directory = Files.createTempDirectory("stealth-verify-");
            Files.delete(directory);
        } catch (IOException e) {
            throw new CleanException("can't create a temporary directory: " + e.getMessage(), e);
        }
        checked(
                git(top, "worktree", "add", "--detach", directory.toString(), ref),
                "check out " + ref + " (is it a branch, tag or commit here?)");
        return directory;
    }

    /** Whether the working tree differs from HEAD, new files included (agents add files too). */
    boolean hasChanges() throws CleanException, InterruptedException {
        return !checked(git(top, "status", "--porcelain"), "read the working tree's status")
                .isBlank();
    }

    boolean branchExists(String branch) throws InterruptedException, CleanException {
        return git(top, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).ok();
    }

    /** Commits {@code files} in the worktree; returns the commit id. */
    String commit(Path worktree, List<String> files, String message)
            throws CleanException, InterruptedException {
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(files);
        checked(git(worktree, add.toArray(String[]::new)), "stage " + files);
        List<String> commit = new ArrayList<>();
        if (!git(worktree, "config", "user.email").ok()) {
            // No identity configured: commit as stealth rather than fail
            commit.addAll(List.of("-c", "user.name=stealth", "-c", "user.email=stealth@localhost"));
        }
        commit.addAll(List.of("commit", "--quiet", "-m", message));
        checked(git(worktree, commit.toArray(String[]::new)), "commit");
        return checked(git(worktree, "rev-parse", "HEAD"), "read the commit").strip();
    }

    /** Drops the worktree's last commit (a patch that broke the build). Our branch only. */
    void dropLastCommit(Path worktree) throws CleanException, InterruptedException {
        checked(git(worktree, "reset", "--hard", "--quiet", "HEAD~1"), "drop the last commit");
    }

    /** Resets the worktree to {@code commit}, discarding our patch commits after it. */
    void resetTo(Path worktree, String commit) throws CleanException, InterruptedException {
        checked(git(worktree, "reset", "--hard", "--quiet", commit), "reset the cleanup branch");
    }

    /** Removes the worktree directory; the branch stays. */
    void removeWorktree(Path worktree) throws InterruptedException {
        try {
            git(top, "worktree", "remove", "--force", worktree.toString());
        } catch (CleanException e) {
            // Best effort: git prunes stale worktrees itself
        }
    }

    /** Deletes a branch this run created and never committed to. */
    void deleteBranch(String branch) throws InterruptedException {
        try {
            git(top, "branch", "-D", branch);
        } catch (CleanException e) {
            // Leaves an empty branch behind at worst
        }
    }

    private static String checked(Command.Result result, String what) throws CleanException {
        if (!result.ok()) {
            throw new CleanException("git couldn't " + what + ": " + result.tail(5));
        }
        return result.output();
    }

    private static Command.Result git(Path directory, String... arguments)
            throws CleanException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            // Parsed output: git's warnings on stderr (e.g. "LF will be replaced by CRLF" with
            // core.autocrlf on Windows) must not look like changes or paths
            return Command.run(directory, TIMEOUT, command, false);
        } catch (IOException e) {
            throw new CleanException("can't run git (is it installed?): " + e.getMessage(), e);
        }
    }
}
