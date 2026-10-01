package dev.stealth.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/**
 * Reads {@code .stealth.yml} as ADR-0004 specifies. Unknown keys and rule ids are warnings (a newer
 * stealth may know them); invalid values for known keys are errors, since silently ignoring, say, a
 * broken {@code fail-under} would let CI gates pass by accident. Keys are kebab-case; camelCase
 * spellings ({@code javaDistribution}) are accepted too.
 */
public final class StealthConfigLoader {

    public static final String FILE_NAME = ".stealth.yml";

    static final int SUPPORTED_VERSION = 1;

    private static final Set<String> SEVERITY_LEVELS =
            Set.of("critical", "high", "medium", "low", "info", "off");

    private static final Map<String, Set<String>> ANALYZER_KEYS =
            Map.of(
                    "maintenance", Set.of("enabled", "stale-after"),
                    "duplication", Set.of("enabled", "min-tokens", "include-tests"),
                    "hygiene", Set.of("enabled", "stale-branch-after", "large-file-bytes"));

    private StealthConfigLoader() {}

    /**
     * The config and what it warned about.
     *
     * @param file the file read, if there was one
     */
    public record Loaded(StealthConfig config, List<String> warnings, Optional<Path> file) {

        public Loaded {
            warnings = List.copyOf(warnings);
        }
    }

    /** {@code .stealth.yml} at {@code root}, or the defaults if there isn't one. */
    public static Loaded load(Path root, Set<String> knownRules, Set<String> knownAnalyzers)
            throws ConfigException {
        Path yml = root.resolve(FILE_NAME);
        Path yaml = root.resolve(".stealth.yaml");
        if (Files.isRegularFile(yml) && Files.isRegularFile(yaml)) {
            throw new ConfigException(
                    "both .stealth.yml and .stealth.yaml exist; keep one of them");
        }
        Path file = Files.isRegularFile(yaml) ? yaml : yml;
        if (!Files.isRegularFile(file)) {
            return new Loaded(StealthConfig.defaults(), List.of(), Optional.empty());
        }
        return loadFile(file, file.getFileName().toString(), knownRules, knownAnalyzers);
    }

    /** A config file passed explicitly, e.g. with {@code --config}. */
    public static Loaded loadFile(
            Path file, String displayName, Set<String> knownRules, Set<String> knownAnalyzers)
            throws ConfigException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(displayName + ": can't read: " + e.getMessage());
        }
        Loaded parsed = parse(text, displayName, knownRules, knownAnalyzers);
        return new Loaded(parsed.config(), parsed.warnings(), Optional.of(file));
    }

    /** Parses {@code text}; {@code displayName} prefixes every message. */
    public static Loaded parse(
            String text, String displayName, Set<String> knownRules, Set<String> knownAnalyzers)
            throws ConfigException {
        Optional<Node> root;
        try {
            root =
                    new Compose(LoadSettings.builder().setLabel(displayName).build())
                            .composeString(text);
        } catch (MarkedYamlEngineException e) {
            int line = e.getProblemMark().map(m -> m.getLine() + 1).orElse(1);
            throw new ConfigException(
                    displayName + ":" + line + ": invalid YAML: " + e.getProblem());
        } catch (YamlEngineException e) {
            throw new ConfigException(displayName + ": invalid YAML: " + e.getMessage());
        }
        Parser parser = new Parser(displayName, knownRules, knownAnalyzers);
        if (root.isEmpty()) {
            return new Loaded(StealthConfig.defaults(), List.of(), Optional.empty());
        }
        return new Loaded(parser.config(root.get()), parser.warnings, Optional.empty());
    }

    /** One file's worth of parsing, collecting warnings as it goes. */
    private static final class Parser {

        private final String file;
        private final Set<String> knownRules;
        private final Set<String> knownAnalyzers;
        private final List<String> warnings = new ArrayList<>();

        Parser(String file, Set<String> knownRules, Set<String> knownAnalyzers) {
            this.file = file;
            this.knownRules = knownRules;
            this.knownAnalyzers = knownAnalyzers;
        }

        StealthConfig config(Node root) throws ConfigException {
            Map<String, Node> keys =
                    mapping(
                            root,
                            "the file",
                            Set.of(
                                    "version",
                                    "ignore",
                                    "severity",
                                    "allow",
                                    "analyzers",
                                    "eol",
                                    "fail-under"));

            Node version = keys.get("version");
            if (version == null) {
                warnings.add(file + ":1: no 'version'; reading it as version " + SUPPORTED_VERSION);
            } else {
                int v = integer(version, "version");
                if (v > SUPPORTED_VERSION) {
                    throw error(
                            version,
                            "version "
                                    + v
                                    + " needs a newer stealth; this one reads version "
                                    + SUPPORTED_VERSION);
                }
                if (v < 1) {
                    throw error(version, "version must be 1");
                }
            }

            List<PathGlob> ignore = new ArrayList<>();
            if (keys.containsKey("ignore")) {
                for (Node entry : sequence(keys.get("ignore"), "ignore")) {
                    String glob = string(entry, "ignore entries");
                    try {
                        ignore.add(PathGlob.of(glob));
                    } catch (IllegalArgumentException e) {
                        throw error(entry, "invalid ignore glob '" + glob + "': " + e.getMessage());
                    }
                }
            }

            Map<String, Optional<Severity>> severity = new HashMap<>();
            if (keys.containsKey("severity")) {
                for (NodeTuple tuple : mappingNode(keys.get("severity"), "severity").getValue()) {
                    String rule = string(tuple.getKeyNode(), "severity keys");
                    String level =
                            string(tuple.getValueNode(), "severity values")
                                    .toLowerCase(Locale.ROOT);
                    if (!SEVERITY_LEVELS.contains(level)) {
                        throw error(
                                tuple.getValueNode(),
                                "unknown severity '"
                                        + level
                                        + "' for "
                                        + rule
                                        + "; use one of critical, high, medium, low, info, off");
                    }
                    warnIfUnknownRule(tuple.getKeyNode(), rule);
                    severity.put(
                            rule,
                            level.equals("off")
                                    ? Optional.empty()
                                    : Optional.of(
                                            Severity.valueOf(level.toUpperCase(Locale.ROOT))));
                }
            }

            List<StealthConfig.AllowedSecret> allowedSecrets = new ArrayList<>();
            List<StealthConfig.AllowedDependency> allowedDependencies = new ArrayList<>();
            if (keys.containsKey("allow")) {
                Map<String, Node> allow =
                        mapping(keys.get("allow"), "allow", Set.of("secrets", "dependencies"));
                if (allow.containsKey("secrets")) {
                    for (Node entry : sequence(allow.get("secrets"), "allow.secrets")) {
                        allowedSecrets.add(allowedSecret(entry));
                    }
                }
                if (allow.containsKey("dependencies")) {
                    for (Node entry : sequence(allow.get("dependencies"), "allow.dependencies")) {
                        allowedDependencies.add(allowedDependency(entry));
                    }
                }
            }

            Set<String> disabled = new LinkedHashSet<>();
            StealthConfig.Thresholds thresholds = StealthConfig.Thresholds.defaults();
            if (keys.containsKey("analyzers")) {
                thresholds = analyzers(keys.get("analyzers"), disabled);
            }

            StealthConfig.Eol eol = StealthConfig.Eol.defaults();
            if (keys.containsKey("eol")) {
                Map<String, Node> section =
                        mapping(
                                keys.get("eol"),
                                "eol",
                                Set.of("java-distribution", "java-version"));
                eol =
                        new StealthConfig.Eol(
                                section.containsKey("java-distribution")
                                        ? string(
                                                section.get("java-distribution"),
                                                "eol.java-distribution")
                                        : StealthConfig.Eol.DEFAULT_JAVA_DISTRIBUTION,
                                section.containsKey("java-version")
                                        ? Optional.of(
                                                string(
                                                        section.get("java-version"),
                                                        "eol.java-version"))
                                        : Optional.empty());
            }

            StealthConfig.FailUnder failUnder = StealthConfig.FailUnder.none();
            if (keys.containsKey("fail-under")) {
                failUnder = failUnder(keys.get("fail-under"));
            }

            return new StealthConfig(
                    disabled,
                    eol,
                    new StealthConfig.Secrets(allowedSecrets),
                    ignore,
                    severity,
                    allowedDependencies,
                    thresholds,
                    failUnder);
        }

        private StealthConfig.AllowedSecret allowedSecret(Node node) throws ConfigException {
            Map<String, Node> entry =
                    mapping(
                            node,
                            "allow.secrets entries",
                            Set.of("path", "fingerprint", "rules", "reason", "expires"));
            if (!entry.containsKey("path") && !entry.containsKey("fingerprint")) {
                throw error(node, "an allow.secrets entry needs a 'path' or a 'fingerprint'");
            }
            Optional<PathGlob> path = Optional.empty();
            if (entry.containsKey("path")) {
                String glob = string(entry.get("path"), "allow.secrets path");
                try {
                    path = Optional.of(PathGlob.of(glob));
                } catch (IllegalArgumentException e) {
                    throw error(
                            entry.get("path"),
                            "invalid path glob '" + glob + "': " + e.getMessage());
                }
            }
            return new StealthConfig.AllowedSecret(
                    path,
                    optionalString(entry.get("fingerprint"), "allow.secrets fingerprint"),
                    rules(entry.get("rules")),
                    reason(node, entry),
                    date(entry.get("expires")));
        }

        private StealthConfig.AllowedDependency allowedDependency(Node node)
                throws ConfigException {
            Map<String, Node> entry =
                    mapping(
                            node,
                            "allow.dependencies entries",
                            Set.of("component", "advisory", "rules", "reason", "expires"));
            if (!entry.containsKey("component") && !entry.containsKey("advisory")) {
                throw error(
                        node, "an allow.dependencies entry needs a 'component' or an 'advisory'");
            }
            Optional<String> component =
                    optionalString(entry.get("component"), "allow.dependencies component");
            if (component.isPresent() && !component.get().startsWith("pkg:")) {
                throw error(
                        entry.get("component"),
                        "component must be a Package URL, e.g."
                                + " pkg:maven/commons-collections/commons-collections");
            }
            return new StealthConfig.AllowedDependency(
                    component,
                    optionalString(entry.get("advisory"), "allow.dependencies advisory"),
                    rules(entry.get("rules")),
                    reason(node, entry),
                    date(entry.get("expires")));
        }

        private StealthConfig.Thresholds analyzers(Node node, Set<String> disabled)
                throws ConfigException {
            Optional<Period> staleAfter = Optional.empty();
            OptionalInt minTokens = OptionalInt.empty();
            Optional<Boolean> includeTests = Optional.empty();
            Optional<Period> staleBranchAfter = Optional.empty();
            OptionalLong largeFileBytes = OptionalLong.empty();
            for (NodeTuple tuple : mappingNode(node, "analyzers").getValue()) {
                String id = string(tuple.getKeyNode(), "analyzer ids");
                if (!knownAnalyzers.isEmpty() && !knownAnalyzers.contains(id)) {
                    warnings.add(
                            at(tuple.getKeyNode())
                                    + "unknown analyzer '"
                                    + id
                                    + "'"
                                    + suggestion(id, knownAnalyzers));
                }
                Map<String, Node> settings =
                        mapping(
                                tuple.getValueNode(),
                                "analyzers." + id,
                                ANALYZER_KEYS.getOrDefault(id, Set.of("enabled")));
                if (settings.containsKey("enabled")
                        && !bool(settings.get("enabled"), "analyzers." + id + ".enabled")) {
                    disabled.add(id);
                }
                switch (id) {
                    case "maintenance" -> {
                        if (settings.containsKey("stale-after")) {
                            staleAfter =
                                    Optional.of(
                                            period(
                                                    settings.get("stale-after"),
                                                    "analyzers.maintenance.stale-after"));
                        }
                    }
                    case "duplication" -> {
                        if (settings.containsKey("min-tokens")) {
                            int tokens =
                                    integer(
                                            settings.get("min-tokens"),
                                            "analyzers.duplication.min-tokens");
                            if (tokens < 1) {
                                throw error(
                                        settings.get("min-tokens"),
                                        "min-tokens must be at least 1");
                            }
                            minTokens = OptionalInt.of(tokens);
                        }
                        if (settings.containsKey("include-tests")) {
                            includeTests =
                                    Optional.of(
                                            bool(
                                                    settings.get("include-tests"),
                                                    "analyzers.duplication.include-tests"));
                        }
                    }
                    case "hygiene" -> {
                        if (settings.containsKey("stale-branch-after")) {
                            staleBranchAfter =
                                    Optional.of(
                                            period(
                                                    settings.get("stale-branch-after"),
                                                    "analyzers.hygiene.stale-branch-after"));
                        }
                        if (settings.containsKey("large-file-bytes")) {
                            long bytes =
                                    longValue(
                                            settings.get("large-file-bytes"),
                                            "analyzers.hygiene.large-file-bytes");
                            if (bytes < 1) {
                                throw error(
                                        settings.get("large-file-bytes"),
                                        "large-file-bytes must be at least 1");
                            }
                            largeFileBytes = OptionalLong.of(bytes);
                        }
                    }
                    default -> {
                        // Only `enabled` applies to the other analyzers
                    }
                }
            }
            return new StealthConfig.Thresholds(
                    staleAfter, minTokens, includeTests, staleBranchAfter, largeFileBytes);
        }

        private StealthConfig.FailUnder failUnder(Node node) throws ConfigException {
            if (node instanceof ScalarNode) {
                return StealthConfig.FailUnder.none().withOverall(score(node, "fail-under"));
            }
            Map<String, Node> section =
                    mapping(node, "fail-under", Set.of("overall", "security", "tech"));
            return new StealthConfig.FailUnder(
                    section.containsKey("overall")
                            ? OptionalInt.of(score(section.get("overall"), "fail-under.overall"))
                            : OptionalInt.empty(),
                    section.containsKey("security")
                            ? OptionalInt.of(score(section.get("security"), "fail-under.security"))
                            : OptionalInt.empty(),
                    section.containsKey("tech")
                            ? OptionalInt.of(score(section.get("tech"), "fail-under.tech"))
                            : OptionalInt.empty());
        }

        private int score(Node node, String what) throws ConfigException {
            int value = integer(node, what);
            if (value < 0 || value > 100) {
                throw error(node, what + " must be between 0 and 100, got " + value);
            }
            return value;
        }

        private Set<String> rules(Node node) throws ConfigException {
            if (node == null) {
                return Set.of();
            }
            Set<String> rules = new LinkedHashSet<>();
            for (Node entry : sequence(node, "rules")) {
                String rule = string(entry, "rules entries");
                warnIfUnknownRule(entry, rule);
                rules.add(rule);
            }
            return rules;
        }

        private String reason(Node entryNode, Map<String, Node> entry) throws ConfigException {
            if (!entry.containsKey("reason")) {
                warnings.add(at(entryNode) + "allow entry has no 'reason'; say why it's accepted");
                return "";
            }
            return string(entry.get("reason"), "reason");
        }

        private void warnIfUnknownRule(Node node, String rule) {
            if (!knownRules.isEmpty() && !knownRules.contains(rule)) {
                warnings.add(
                        at(node) + "unknown rule '" + rule + "'" + suggestion(rule, knownRules));
            }
        }

        /** A mapping's entries by normalised key, warning about keys not in {@code known}. */
        private Map<String, Node> mapping(Node node, String what, Set<String> known)
                throws ConfigException {
            Map<String, Node> entries = new HashMap<>();
            for (NodeTuple tuple : mappingNode(node, what).getValue()) {
                String key = kebab(string(tuple.getKeyNode(), what + " keys"));
                if (!known.contains(key)) {
                    warnings.add(
                            at(tuple.getKeyNode())
                                    + "unknown key '"
                                    + key
                                    + "'"
                                    + suggestion(key, known));
                    continue;
                }
                if (entries.put(key, tuple.getValueNode()) != null) {
                    throw error(tuple.getKeyNode(), "'" + key + "' appears twice");
                }
            }
            return entries;
        }

        private MappingNode mappingNode(Node node, String what) throws ConfigException {
            if (node instanceof MappingNode mapping) {
                return mapping;
            }
            throw error(node, what + " must be a mapping (key: value)");
        }

        private List<Node> sequence(Node node, String what) throws ConfigException {
            if (node instanceof SequenceNode sequence) {
                return sequence.getValue();
            }
            throw error(node, what + " must be a list");
        }

        private String string(Node node, String what) throws ConfigException {
            if (node instanceof ScalarNode scalar && !scalar.getValue().isBlank()) {
                return scalar.getValue();
            }
            throw error(node, what + " must be a non-empty string");
        }

        private Optional<String> optionalString(Node node, String what) throws ConfigException {
            return node == null ? Optional.empty() : Optional.of(string(node, what));
        }

        private int integer(Node node, String what) throws ConfigException {
            String value = string(node, what);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw error(node, what + " must be a whole number, got '" + value + "'");
            }
        }

        private long longValue(Node node, String what) throws ConfigException {
            String value = string(node, what);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw error(node, what + " must be a whole number, got '" + value + "'");
            }
        }

        private boolean bool(Node node, String what) throws ConfigException {
            String value = string(node, what).toLowerCase(Locale.ROOT);
            return switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw error(node, what + " must be true or false, got '" + value + "'");
            };
        }

        private Period period(Node node, String what) throws ConfigException {
            String value = string(node, what);
            try {
                return Period.parse(value);
            } catch (DateTimeParseException e) {
                throw error(
                        node,
                        what
                                + " must be an ISO-8601 period such as P2Y or P90D, got '"
                                + value
                                + "'");
            }
        }

        private Optional<LocalDate> date(Node node) throws ConfigException {
            if (node == null) {
                return Optional.empty();
            }
            String value = string(node, "expires");
            try {
                return Optional.of(LocalDate.parse(value));
            } catch (DateTimeParseException e) {
                throw error(node, "expires must be a date such as 2026-12-31, got '" + value + "'");
            }
        }

        private ConfigException error(Node node, String message) {
            return new ConfigException(at(node) + message);
        }

        private String at(Node node) {
            int line = node.getStartMark().map(mark -> mark.getLine() + 1).orElse(1);
            return file + ":" + line + ": ";
        }
    }

    /** {@code staleAfter} → {@code stale-after}; kebab-case keys are returned as they are. */
    static String kebab(String key) {
        StringBuilder out = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** " (did you mean 'ignore'?)" for a close enough known value, otherwise "". */
    static String suggestion(String value, Set<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = distance(value, candidate);
            if (distance < bestDistance
                    || (distance == bestDistance && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        int allowed = Math.max(2, value.length() / 4);
        return best != null && bestDistance <= allowed ? " (did you mean '" + best + "'?)" : "";
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] =
                        Math.min(
                                Math.min(current[j - 1] + 1, previous[j] + 1),
                                previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
