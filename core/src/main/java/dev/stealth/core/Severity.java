package dev.stealth.core;

/** How bad a finding is. {@link #INFO} findings are shown but never affect the score. */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO
}
