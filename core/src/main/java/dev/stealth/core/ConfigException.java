package dev.stealth.core;

/**
 * A {@code .stealth.yml} that can't be used: a syntax error or an invalid value for a known key.
 * The message starts with the file and line, e.g. {@code .stealth.yml:12: fail-under must be
 * between 0 and 100, got 120}. The CLI exits with code 2.
 */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }
}
