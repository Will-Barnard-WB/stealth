package dev.stealth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.RepoContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoScansTest {

    @TempDir private Path repo;

    private final AtomicInteger runs = new AtomicInteger();
    private RepoScans scans;

    @BeforeEach
    void setUp() {
        Analyzer counting =
                new Analyzer() {
                    @Override
                    public String id() {
                        return "counting";
                    }

                    @Override
                    public Category category() {
                        return Category.TECH;
                    }

                    @Override
                    public List<Finding> analyze(RepoContext context) {
                        runs.incrementAndGet();
                        return List.of();
                    }
                };
        scans =
                new RepoScans(
                        new AnalyzerRunner(List.of(counting), Duration.ofSeconds(10)),
                        Clock.systemUTC());
    }

    @Test
    void scan_unchangedGitRepository_reusesTheFirstScan() throws Exception {
        commit("README.md", "hello");

        RepoScans.Scan first = scans.scan(repo, false);
        RepoScans.Scan second = scans.scan(repo, false);

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void scan_afterAnEditOrNewFile_scansAgain() throws Exception {
        commit("README.md", "hello");
        scans.scan(repo, false);

        Files.writeString(repo.resolve("README.md"), "edited");
        scans.scan(repo, false);
        Files.writeString(repo.resolve("New.java"), "class New {}");
        scans.scan(repo, false);
        // Edited again to the same length: the modification time still differs
        Path readme = repo.resolve("README.md");
        Files.writeString(readme, "EDITED");
        Files.setLastModifiedTime(readme, FileTime.from(Instant.now().plusSeconds(5)));
        scans.scan(repo, false);

        assertThat(runs).hasValue(4);
    }

    @Test
    void scan_refresh_alwaysScans() throws Exception {
        commit("README.md", "hello");

        scans.scan(repo, false);
        RepoScans.Scan refreshed = scans.scan(repo, true);

        assertThat(refreshed.cached()).isFalse();
        assertThat(runs).hasValue(2);
    }

    @Test
    void scan_outsideGit_neverCaches() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project/>");

        scans.scan(repo, false);
        scans.scan(repo, false);

        assertThat(runs).hasValue(2);
    }

    @Test
    void scan_olderThanTheTimeToLive_scansAgain() throws Exception {
        commit("README.md", "hello");
        MutableClock clock = new MutableClock();
        RepoScans timed =
                new RepoScans(
                        new AnalyzerRunner(
                                List.of(
                                        new Analyzer() {
                                            @Override
                                            public String id() {
                                                return "counting";
                                            }

                                            @Override
                                            public Category category() {
                                                return Category.TECH;
                                            }

                                            @Override
                                            public List<Finding> analyze(RepoContext context) {
                                                runs.incrementAndGet();
                                                return List.of();
                                            }
                                        }),
                                Duration.ofSeconds(10)),
                        clock);

        timed.scan(repo, false);
        clock.now = clock.now.plus(RepoScans.TIME_TO_LIVE).plusSeconds(1);
        timed.scan(repo, false);

        assertThat(runs).hasValue(2);
    }

    private void commit(String file, String content) throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            Files.writeString(repo.resolve(file), content);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("t", "t@example.com")
                    .setCommitter("t", "t@example.com")
                    .setSign(false)
                    .call();
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
