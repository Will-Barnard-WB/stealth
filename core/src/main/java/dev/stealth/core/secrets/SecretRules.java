package dev.stealth.core.secrets;

import dev.stealth.core.Category;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Loads the secret rules bundled in {@code rules.yml}, next to this class. */
final class SecretRules {

    static final String HELP =
            "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/secrets.md";

    private static final String RESOURCE = "rules.yml";

    private SecretRules() {}

    /** The bundled rules, in file order. A broken ruleset is a bug in stealth, so it throws. */
    static List<SecretRule> bundled() {
        try (InputStream in = SecretRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " next to SecretRules");
            }
            return parse(YAMLMapper.builder().build().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + RESOURCE, e);
        }
    }

    static List<SecretRule> parse(JsonNode root) {
        List<SecretRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : root.path("rules")) {
            String id = required(node, "id");
            if (!id.startsWith("secrets/") || !ids.add(id)) {
                throw new IllegalStateException("Secret rule ids must be unique secrets/*: " + id);
            }
            Rule rule =
                    new Rule(
                            id,
                            required(node, "name"),
                            required(node, "description"),
                            URI.create(HELP + "#" + id.replace('/', '-')),
                            Category.SECURITY,
                            Severity.valueOf(required(node, "severity").toUpperCase(Locale.ROOT)));
            List<String> keywords = new ArrayList<>();
            node.path("keywords").forEach(k -> keywords.add(k.asString()));
            if (keywords.isEmpty()) {
                throw new IllegalStateException(id + " needs keywords");
            }
            OptionalDouble minEntropy =
                    node.has("min-entropy")
                            ? OptionalDouble.of(node.get("min-entropy").asDouble())
                            : OptionalDouble.empty();
            List<SecretRule.Detector> detectors = new ArrayList<>();
            for (JsonNode pattern : node.path("patterns")) {
                Set<String> extensions = new HashSet<>();
                pattern.path("files").forEach(f -> extensions.add(f.asString()));
                detectors.add(new SecretRule.Detector(compile(id, pattern), extensions));
            }
            if (detectors.isEmpty()) {
                throw new IllegalStateException(id + " needs patterns");
            }
            rules.add(new SecretRule(rule, keywords, minEntropy, detectors));
        }
        return List.copyOf(rules);
    }

    private static Pattern compile(String id, JsonNode pattern) {
        try {
            return Pattern.compile(required(pattern, "regex"));
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(id + " has an invalid regex", e);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException("Secret rule is missing '" + field + "': " + node);
        }
        return value.asString();
    }
}
