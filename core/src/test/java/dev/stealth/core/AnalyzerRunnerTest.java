package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AnalyzerRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RepoContext context = new RepoContext(Path.of("repo"), StealthConfig.defaults());

    @Test
    void run_analyzersWaitForEachOther_runsThemInParallel() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Finding outdated = finding("deps/outdated");
        Finding secret = finding("secrets/aws-access-key");
        Analyzer deps = new StubAnalyzer("deps", ctx -> awaitOther(bothStarted, outdated));
        Analyzer secrets = new StubAnalyzer("secrets", ctx -> awaitOther(bothStarted, secret));

        DoctorReport report = new AnalyzerRunner(List.of(deps, secrets), TIMEOUT).run(context);

        assertThat(report.results())
                .extracting(AnalyzerResult::analyzerId, AnalyzerResult::status)
                .containsExactly(
                        tuple("deps", AnalyzerStatus.OK), tuple("secrets", AnalyzerStatus.OK));
        assertThat(report.findings()).containsExactly(outdated, secret);
    }

    @Test
    void run_analyzerExceedsTimeout_marksItTimedOutWithoutBlockingOthers() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        Analyzer slow =
                new StubAnalyzer(
                        "vuln",
                        ctx -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                            }
                            return List.of();
                        });
        Finding missingCodeowners = finding("hygiene/missing-codeowners");
        Analyzer fast = new StubAnalyzer("hygiene", ctx -> List.of(missingCodeowners));
        AnalyzerRunner runner = new AnalyzerRunner(List.of(slow, fast), Duration.ofMillis(200));

        long start = System.nanoTime();
        DoctorReport report = runner.run(context);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(report.results().get(0).status()).isEqualTo(AnalyzerStatus.TIMED_OUT);
        assertThat(report.results().get(0).error()).isPresent();
        assertThat(report.results().get(1).status()).isEqualTo(AnalyzerStatus.OK);
        assertThat(report.findings()).containsExactly(missingCodeowners);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("slow analyzer interrupted").isTrue();
    }

    @Test
    void run_analyzerThrows_marksItFailedAndKeepsOtherFindings() throws Exception {
        Analyzer broken =
                new StubAnalyzer(
                        "eol",
                        ctx -> {
                            throw new IllegalStateException("endoflife.date returned 500");
                        });
        Finding outdated = finding("deps/outdated");
        Analyzer deps = new StubAnalyzer("deps", ctx -> List.of(outdated));

        DoctorReport report = new AnalyzerRunner(List.of(broken, deps), TIMEOUT).run(context);

        AnalyzerResult failed = report.results().get(0);
        assertThat(failed.status()).isEqualTo(AnalyzerStatus.FAILED);
        assertThat(failed.error()).hasValueSatisfying(error -> assertThat(error).contains("500"));
        assertThat(report.results().get(1).status()).isEqualTo(AnalyzerStatus.OK);
        assertThat(report.findings()).containsExactly(outdated);
    }

    @Test
    void run_analyzerReturnsNull_marksItFailed() throws Exception {
        Analyzer analyzer = new StubAnalyzer("deps", ctx -> null);

        DoctorReport report = new AnalyzerRunner(List.of(analyzer), TIMEOUT).run(context);

        assertThat(report.results())
                .singleElement()
                .extracting(AnalyzerResult::status)
                .isEqualTo(AnalyzerStatus.FAILED);
    }

    @Test
    void run_analyzerDisabled_marksItSkippedWithoutRunning() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Analyzer duplication =
                new StubAnalyzer(
                        "duplication",
                        ctx -> {
                            ran.set(true);
                            return List.of(finding("duplication/cpd"));
                        });
        RepoContext disabled =
                new RepoContext(Path.of("repo"), new StealthConfig(Set.of("duplication")));

        DoctorReport report = new AnalyzerRunner(List.of(duplication), TIMEOUT).run(disabled);

        assertThat(report.results())
                .singleElement()
                .extracting(AnalyzerResult::status)
                .isEqualTo(AnalyzerStatus.SKIPPED);
        assertThat(report.findings()).isEmpty();
        assertThat(ran).isFalse();
    }

    @Test
    void run_analyzerNotSelected_leavesItOutWithoutRunning() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Analyzer deps =
                new StubAnalyzer(
                        "deps",
                        ctx -> {
                            ran.set(true);
                            return List.of(finding("deps/outdated-major"));
                        });
        Analyzer secrets = new StubAnalyzer("secrets", ctx -> List.of(finding("secrets/aws")));

        DoctorReport report =
                new AnalyzerRunner(List.of(deps, secrets), TIMEOUT)
                        .run(context, analyzer -> analyzer.id().equals("secrets"));

        assertThat(report.results())
                .extracting(AnalyzerResult::analyzerId)
                .containsExactly("secrets");
        assertThat(report.findings()).extracting(Finding::ruleId).containsExactly("secrets/aws");
        assertThat(ran).isFalse();
    }

    @Test
    void run_noAnalyzers_returnsEmptyReport() throws Exception {
        DoctorReport report = new AnalyzerRunner(List.of(), TIMEOUT).run(context);

        assertThat(report.findings()).isEmpty();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void new_duplicateAnalyzerIds_throws() {
        List<Analyzer> analyzers =
                List.of(
                        new StubAnalyzer("deps", ctx -> List.of()),
                        new StubAnalyzer("deps", ctx -> List.of()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AnalyzerRunner(analyzers, TIMEOUT))
                .withMessageContaining("deps");
    }

    private static List<Finding> awaitOther(CountDownLatch bothStarted, Finding finding)
            throws InterruptedException {
        bothStarted.countDown();
        if (!bothStarted.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("analyzers ran one after the other");
        }
        return List.of(finding);
    }

    static Finding finding(String ruleId) {
        return new Finding(
                ruleId,
                Category.TECH,
                Severity.LOW,
                "message for " + ruleId,
                Location.repository(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Fingerprints.of(ruleId));
    }

    @FunctionalInterface
    private interface Body {
        List<Finding> analyze(RepoContext context) throws Exception;
    }

    private record StubAnalyzer(String id, Body body) implements Analyzer {

        @Override
        public Category category() {
            return Category.TECH;
        }

        @Override
        public List<Finding> analyze(RepoContext context) throws Exception {
            return body.analyze(context);
        }
    }
}
