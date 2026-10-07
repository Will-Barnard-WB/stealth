package dev.stealth.core.clean;

/** {@code stealth clean} refused or couldn't finish, with a message saying what to do about it. */
public class CleanException extends Exception {

    public CleanException(String message) {
        super(message);
    }

    public CleanException(String message, Throwable cause) {
        super(message, cause);
    }
}
