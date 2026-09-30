package dev.stealth.core.eol;

import java.time.LocalDate;
import java.util.Objects;

/**
 * How long a release cycle is supported for. endoflife.date gives either a date or a boolean, so
 * "supported until further notice" and "over, date unknown" are distinct from a known end date.
 */
public sealed interface Support {

    /** Support ends on {@code date}, which is the last supported day. */
    record Until(LocalDate date) implements Support {
        public Until {
            Objects.requireNonNull(date, "date");
        }
    }

    /** Over, with no date given ({@code "eol": true}). */
    record Ended() implements Support {}

    /** No end announced ({@code "eol": false}, or the field is absent). */
    record Open() implements Support {}

    default boolean endedBy(LocalDate date) {
        return switch (this) {
            case Until(LocalDate end) -> date.isAfter(end);
            case Ended ignored -> true;
            case Open ignored -> false;
        };
    }
}
