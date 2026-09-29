package dev.stealth.core;

import java.util.List;

/**
 * Checks a repository for one kind of tech or security debt. {@link AnalyzerRunner} runs analyzers
 * in parallel, so {@link #analyze} must be thread-safe, and it should respond to interruption,
 * which is how a timed-out analyzer is cancelled.
 */
public interface Analyzer {

    /** Short lowercase id, also the prefix of this analyzer's rule ids, e.g. {@code deps}. */
    String id();

    Category category();

    /** The rules this analyzer can report, for SARIF {@code rules[]} and {@code --list-rules}. */
    default List<Rule> rules() {
        return List.of();
    }

    /** Throwing marks this analyzer failed in the report; it never fails the run. */
    List<Finding> analyze(RepoContext context) throws Exception;
}
