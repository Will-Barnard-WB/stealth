package dev.stealth.core.eol;

import java.util.Objects;
import java.util.Optional;

/**
 * One release cycle of a product on endoflife.date, such as Spring Boot {@code 2.7} or Java {@code
 * 21}.
 *
 * @param support when free support ends, from the API's {@code eol}
 * @param extendedSupport when paid support ends, where the product has any
 * @param latest the newest release in this cycle, which is the version to upgrade to
 */
public record ReleaseCycle(
        String cycle, Support support, Support extendedSupport, Optional<String> latest) {

    public ReleaseCycle {
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(extendedSupport, "extendedSupport");
        Objects.requireNonNull(latest, "latest");
    }
}
