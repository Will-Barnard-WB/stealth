package dev.stealth.core.eol;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A runtime or framework whose support dates stealth tracks. */
enum Product {
    JAVA("java", "Java"),
    SPRING_BOOT("spring-boot", "Spring Boot");

    private static final Pattern JAVA_CYCLE = Pattern.compile("^(?:1\\.)?(\\d+)");
    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+\\.\\d+)");

    private final String id;
    private final String label;

    Product(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** Identifies the product in fingerprints, so it must never change. See ADR-0001. */
    String id() {
        return id;
    }

    String label() {
        return label;
    }

    /** The endoflife.date release cycle {@code version} belongs to, e.g. {@code 2.7.18} to 2.7. */
    Optional<String> cycleOf(String version) {
        Matcher matcher = (this == JAVA ? JAVA_CYCLE : MAJOR_MINOR).matcher(version.trim());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
