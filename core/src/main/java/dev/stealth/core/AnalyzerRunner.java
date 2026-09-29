package dev.stealth.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Runs every enabled analyzer in parallel, each on its own virtual thread with a timeout. A slow or
 * failing analyzer never fails the run or holds up the others: it shows up in the report as {@link
 * AnalyzerStatus#TIMED_OUT} or {@link AnalyzerStatus#FAILED}.
 */
public class AnalyzerRunner {

    private final List<Analyzer> analyzers;
    private final Duration timeout;

    public AnalyzerRunner(List<Analyzer> analyzers, Duration timeout) {
        this.analyzers = List.copyOf(analyzers);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        Set<String> ids = new HashSet<>();
        for (Analyzer analyzer : this.analyzers) {
            if (!ids.add(analyzer.id())) {
                throw new IllegalArgumentException("duplicate analyzer id: " + analyzer.id());
            }
        }
    }

    public DoctorReport run(RepoContext context) throws InterruptedException {
        // Not try-with-resources: close() waits for every task, including timed-out analyzers
        // that ignore interruption. Virtual threads are daemons, so abandoning them is safe.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long start = System.nanoTime();
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Analyzer analyzer : analyzers) {
                futures.add(
                        context.config().isEnabled(analyzer.id())
                                ? executor.submit(() -> analyze(analyzer, context))
                                : null);
            }

            // Every task starts straight away on its own virtual thread, so one shared deadline
            // gives each analyzer the full timeout.
            long deadline = start + timeout.toNanos();
            List<Finding> findings = new ArrayList<>();
            List<AnalyzerResult> results = new ArrayList<>();
            for (int i = 0; i < analyzers.size(); i++) {
                Analyzer analyzer = analyzers.get(i);
                Future<Outcome> future = futures.get(i);
                if (future == null) {
                    results.add(result(analyzer, AnalyzerStatus.SKIPPED, Duration.ZERO, null));
                    continue;
                }
                try {
                    Outcome outcome =
                            future.get(Math.max(0, deadline - System.nanoTime()), NANOSECONDS);
                    findings.addAll(outcome.findings());
                    results.add(outcome.result());
                } catch (TimeoutException e) {
                    future.cancel(true);
                    results.add(
                            result(
                                    analyzer,
                                    AnalyzerStatus.TIMED_OUT,
                                    timeout,
                                    "did not finish within " + timeout.toSeconds() + "s"));
                } catch (ExecutionException e) {
                    // Only Errors get here; analyze() catches exceptions itself
                    results.add(
                            result(
                                    analyzer,
                                    AnalyzerStatus.FAILED,
                                    elapsedSince(start),
                                    e.getCause().toString()));
                }
            }
            return new DoctorReport(findings, results);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Outcome analyze(Analyzer analyzer, RepoContext context) {
        long start = System.nanoTime();
        try {
            List<Finding> findings = List.copyOf(analyzer.analyze(context));
            return new Outcome(
                    findings, result(analyzer, AnalyzerStatus.OK, elapsedSince(start), null));
        } catch (Exception e) {
            return new Outcome(
                    List.of(),
                    result(analyzer, AnalyzerStatus.FAILED, elapsedSince(start), e.toString()));
        }
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static AnalyzerResult result(
            Analyzer analyzer, AnalyzerStatus status, Duration duration, String error) {
        return new AnalyzerResult(
                analyzer.id(), analyzer.category(), status, duration, Optional.ofNullable(error));
    }

    private record Outcome(List<Finding> findings, AnalyzerResult result) {}
}
