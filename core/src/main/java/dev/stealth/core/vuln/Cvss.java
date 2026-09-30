package dev.stealth.core.vuln;

import dev.stealth.core.Severity;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * CVSS v3.0/v3.1 base scores from vector strings, and the ADR-0001 severity bands. OSV gives the
 * vector, not the score. The formula is from the CVSS v3.1 specification, section 7.
 */
final class Cvss {

    private Cvss() {}

    /** The base score of a {@code CVSS:3.x/...} vector, or empty if it isn't a valid v3 vector. */
    static OptionalDouble baseScore(String vector) {
        if (vector == null || !vector.matches("CVSS:3\\.[01]/.*")) {
            return OptionalDouble.empty();
        }
        Map<String, String> metrics = new HashMap<>();
        for (String part : vector.substring(vector.indexOf('/') + 1).split("/")) {
            String[] keyValue = part.split(":");
            if (keyValue.length == 2) {
                metrics.put(keyValue[0], keyValue[1]);
            }
        }
        try {
            boolean scopeChanged = "C".equals(required(metrics, "S"));
            double attackVector =
                    switch (required(metrics, "AV")) {
                        case "N" -> 0.85;
                        case "A" -> 0.62;
                        case "L" -> 0.55;
                        case "P" -> 0.2;
                        default -> throw new IllegalArgumentException();
                    };
            double attackComplexity =
                    switch (required(metrics, "AC")) {
                        case "L" -> 0.77;
                        case "H" -> 0.44;
                        default -> throw new IllegalArgumentException();
                    };
            double privileges =
                    switch (required(metrics, "PR")) {
                        case "N" -> 0.85;
                        case "L" -> scopeChanged ? 0.68 : 0.62;
                        case "H" -> scopeChanged ? 0.5 : 0.27;
                        default -> throw new IllegalArgumentException();
                    };
            double userInteraction =
                    switch (required(metrics, "UI")) {
                        case "N" -> 0.85;
                        case "R" -> 0.62;
                        default -> throw new IllegalArgumentException();
                    };
            double impactSubScore =
                    1
                            - (1 - impact(required(metrics, "C")))
                                    * (1 - impact(required(metrics, "I")))
                                    * (1 - impact(required(metrics, "A")));
            double impact =
                    scopeChanged
                            ? 7.52 * (impactSubScore - 0.029)
                                    - 3.25 * Math.pow(impactSubScore - 0.02, 15)
                            : 6.42 * impactSubScore;
            double exploitability =
                    8.22 * attackVector * attackComplexity * privileges * userInteraction;
            if (impact <= 0) {
                return OptionalDouble.of(0);
            }
            double score =
                    scopeChanged
                            ? Math.min(1.08 * (impact + exploitability), 10)
                            : Math.min(impact + exploitability, 10);
            return OptionalDouble.of(roundUp(score));
        } catch (IllegalArgumentException e) {
            return OptionalDouble.empty();
        }
    }

    /** ADR-0001: ≥ 9.0 critical, 7.0–8.9 high, 4.0–6.9 medium, 0.1–3.9 low. */
    static Severity severity(double score) {
        if (score >= 9.0) {
            return Severity.CRITICAL;
        }
        if (score >= 7.0) {
            return Severity.HIGH;
        }
        if (score >= 4.0) {
            return Severity.MEDIUM;
        }
        return score > 0 ? Severity.LOW : Severity.INFO;
    }

    private static double impact(String value) {
        return switch (value) {
            case "H" -> 0.56;
            case "L" -> 0.22;
            case "N" -> 0;
            default -> throw new IllegalArgumentException();
        };
    }

    private static String required(Map<String, String> metrics, String key) {
        String value = metrics.get(key);
        if (value == null) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    /** The specification's Roundup: the smallest one-decimal number ≥ the input, float-safe. */
    private static double roundUp(double value) {
        long scaled = Math.round(value * 100_000);
        if (scaled % 10_000 == 0) {
            return scaled / 100_000.0;
        }
        return (Math.floor(scaled / 10_000.0) + 1) / 10.0;
    }
}
