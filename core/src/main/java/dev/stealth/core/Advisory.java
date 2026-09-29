package dev.stealth.core;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A published vulnerability advisory.
 *
 * @param id the primary id, e.g. {@code GHSA-jjjh-jjxp-wpff}
 * @param aliases other ids for the same advisory, e.g. {@code CVE-2022-42003}
 */
public record Advisory(
        String id, List<String> aliases, OptionalDouble cvssScore, Optional<URI> url) {

    public Advisory {
        Objects.requireNonNull(id, "id");
        aliases = List.copyOf(aliases);
        Objects.requireNonNull(cvssScore, "cvssScore");
        Objects.requireNonNull(url, "url");
    }
}
