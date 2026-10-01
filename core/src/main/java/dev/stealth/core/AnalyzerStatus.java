package dev.stealth.core;

/** How an analyzer's run ended. */
public enum AnalyzerStatus {
    OK,
    FAILED,
    TIMED_OUT,
    /** Disabled in the config, so it didn't run. */
    SKIPPED,
    /** Left out of this run by a command-line filter such as {@code --hygiene}. */
    NOT_SELECTED
}
