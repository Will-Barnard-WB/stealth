package dev.stealth.core.impact;

import dev.stealth.core.RepoContext;
import dev.stealth.core.maven.DependencyNode;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.Type;

/**
 * What upgrading one dependency would break in this repository: the library's API is diffed between
 * the version the repository resolves now and the target (classes and members removed or newly
 * deprecated), and intersected with what the repository's compiled code references, line by line.
 * The result is the worklist for whoever does the upgrade: usually the agent.
 */
public class UpgradeImpact {

    private static final Pattern IMPORT =
            Pattern.compile(
                    "^\\s*import\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;", Pattern.MULTILINE);

    /** Package and class moves worth knowing about that a jar diff can't infer. */
    private static final Map<String, String> KNOWN_MOVES =
            new LinkedHashMap<>(
                    Map.of(
                            "javax.servlet.", "Jakarta EE 9+: use jakarta.servlet",
                            "javax.persistence.", "Jakarta EE 9+: use jakarta.persistence",
                            "javax.validation.", "Jakarta EE 9+: use jakarta.validation",
                            "javax.annotation.PostConstruct",
                                    "Jakarta EE 9+: use jakarta.annotation.PostConstruct",
                            "javax.annotation.PreDestroy",
                                    "Jakarta EE 9+: use jakarta.annotation.PreDestroy",
                            "javax.transaction.", "Jakarta EE 9+: use jakarta.transaction",
                            "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
                                    "Spring Security 5.7+: declare a SecurityFilterChain @Bean"
                                            + " instead",
                            "org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter",
                                    "Spring 5+: implement WebMvcConfigurer directly"));

    private final MavenModelLoader loader;

    public UpgradeImpact(MavenModelLoader loader) {
        this.loader = loader;
    }

    public enum Kind {
        /**
         * Gone in the new version: the code won't compile (or will fail at runtime) until changed.
         */
        REMOVED,
        /** Still there, but deprecated in the new version: works now, plan to move off it. */
        DEPRECATED
    }

    /**
     * One place the upgrade affects.
     *
     * @param where {@code file:line}
     * @param api the class or member used, e.g. {@code com.google.common.io.Files#createTempDir()}
     * @param hint what to use instead, when known
     */
    public record Usage(String where, String api, Kind kind, Optional<String> hint) {}

    /**
     * @param precise whether usages come from compiled classes (exact, with lines) or, when the
     *     project isn't compiled, from imports in the sources (approximate: classes only)
     * @param removed how many APIs the new version removes (used here or not)
     * @param deprecated how many it newly deprecates
     */
    public record Result(
            String dependency,
            String from,
            String to,
            boolean precise,
            int removed,
            int deprecated,
            List<Usage> usages,
            List<String> notes) {

        public long breaking() {
            return usages.stream().filter(u -> u.kind() == Kind.REMOVED).count();
        }
    }

    /**
     * @throws IllegalArgumentException if the dependency isn't in this repository's tree
     * @throws IOException if a jar can't be downloaded or read
     */
    public Result analyze(RepoContext context, String groupId, String artifactId, String to)
            throws IOException {
        String key = groupId + ":" + artifactId;
        String from =
                currentVersion(context.get(loader), key)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                key + " isn't a dependency of this repository"));
        Path oldJar = jar(groupId, artifactId, from);
        Path newJar = jar(groupId, artifactId, to);
        ApiSurface before = ApiSurface.of(oldJar);
        ApiSurface after = ApiSurface.of(newJar);
        ApiChanges changes = ApiChanges.between(before, after);
        int removed =
                changes.removedClasses().size()
                        + changes.removedMembers().values().stream().mapToInt(Set::size).sum();
        int deprecated =
                changes.deprecatedClasses().size()
                        + changes.deprecatedMembers().values().stream().mapToInt(Set::size).sum();

        List<String> notes = new ArrayList<>();
        List<Path> compiled = UsageScanner.classDirectories(context.root());
        List<Usage> usages;
        boolean precise = !compiled.isEmpty();
        if (precise) {
            usages = fromBytecode(UsageScanner.scan(context.root(), compiled), changes, after);
        } else {
            usages = fromImports(context.root(), changes, after);
            notes.add(
                    "The project isn't compiled, so only imports of removed or deprecated classes"
                            + " were matched. Compile it (./mvnw compile) for exact call sites,"
                            + " including removed methods.");
        }
        return new Result(key, from, to, precise, removed, deprecated, usages, notes);
    }

    /** The version of {@code key} the repository resolves (the most common, across modules). */
    static Optional<String> currentVersion(MavenProjectModel model, String key) {
        Map<String, Integer> counts = new HashMap<>();
        for (MavenModule module : model.modules()) {
            module.dependency(key.split(":")[0], key.split(":")[1])
                    .ifPresent(d -> counts.merge(d.version(), 1, Integer::sum));
            for (DependencyNode root : module.dependencyTree()) {
                root.walk(
                        path -> {
                            DependencyNode node = path.getLast();
                            if (node.key().equals(key)) {
                                counts.merge(node.version(), 1, Integer::sum);
                            }
                        });
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    private Path jar(String groupId, String artifactId, String version) throws IOException {
        return loader.resolve(groupId, artifactId, version, "jar")
                .orElseThrow(
                        () ->
                                new IOException(
                                        "can't download "
                                                + groupId
                                                + ":"
                                                + artifactId
                                                + ":"
                                                + version
                                                + " (is the version published?)"));
    }

    static List<Usage> fromBytecode(
            List<UsageScanner.Reference> references, ApiChanges changes, ApiSurface after) {
        Map<String, Usage> usages = new LinkedHashMap<>();
        for (UsageScanner.Reference reference : references) {
            String owner = reference.owner();
            String where = reference.source() + ":" + reference.line();
            if (reference.member() == null) {
                if (changes.removedClasses().contains(owner)) {
                    String api = dotted(owner);
                    usages.putIfAbsent(
                            where + api,
                            new Usage(where, api, Kind.REMOVED, classHint(owner, after)));
                } else if (changes.deprecatedClasses().contains(owner)) {
                    String api = dotted(owner);
                    usages.putIfAbsent(
                            where + api, new Usage(where, api, Kind.DEPRECATED, Optional.empty()));
                }
                continue;
            }
            if (changes.removedMembers()
                    .getOrDefault(owner, Set.of())
                    .contains(reference.member())) {
                String api = member(owner, reference.member());
                usages.putIfAbsent(
                        where + api,
                        new Usage(
                                where,
                                api,
                                Kind.REMOVED,
                                memberHint(owner, reference.member(), after)));
            } else if (changes.deprecatedMembers()
                    .getOrDefault(owner, Set.of())
                    .contains(reference.member())) {
                String api = member(owner, reference.member());
                usages.putIfAbsent(
                        where + api, new Usage(where, api, Kind.DEPRECATED, Optional.empty()));
            }
        }
        return sorted(usages.values());
    }

    /** Without compiled classes: imports of removed or deprecated classes (and their packages). */
    static List<Usage> fromImports(Path root, ApiChanges changes, ApiSurface after)
            throws IOException {
        Set<String> removed =
                changes.removedClasses().stream()
                        .map(UpgradeImpact::dotted)
                        .collect(Collectors.toSet());
        Set<String> deprecated =
                changes.deprecatedClasses().stream()
                        .map(UpgradeImpact::dotted)
                        .collect(Collectors.toSet());
        List<Usage> usages = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(
                                    f ->
                                            !f.toString().contains("/target/")
                                                    && !f.toString().contains("\\target\\"))
                            .toList()) {
                String text = Files.readString(file);
                Matcher matcher = IMPORT.matcher(text);
                while (matcher.find()) {
                    String name = matcher.group(2);
                    int line = (int) text.substring(0, matcher.start()).lines().count() + 1;
                    String where = root.relativize(file).toString().replace('\\', '/') + ":" + line;
                    if (removed.contains(name)) {
                        usages.add(
                                new Usage(
                                        where,
                                        name,
                                        Kind.REMOVED,
                                        classHint(name.replace('.', '/'), after)));
                    } else if (deprecated.contains(name)) {
                        usages.add(new Usage(where, name, Kind.DEPRECATED, Optional.empty()));
                    }
                }
            }
        }
        return sorted(usages);
    }

    /** A known move, or a class with the same simple name elsewhere in the new version. */
    static Optional<String> classHint(String internalName, ApiSurface after) {
        String dotted = dotted(internalName);
        for (Map.Entry<String, String> move : KNOWN_MOVES.entrySet()) {
            if (dotted.startsWith(move.getKey())) {
                return Optional.of(move.getValue());
            }
        }
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        Set<String> candidates = new LinkedHashSet<>();
        for (String name : after.classes().keySet()) {
            if (name.endsWith("/" + simple) && !name.equals(internalName)) {
                candidates.add(dotted(name));
            }
        }
        if (candidates.size() == 1) {
            return Optional.of("moved to " + candidates.iterator().next() + "?");
        }
        return Optional.empty();
    }

    /** The same method under another signature in the new version, or a known move in its types. */
    static Optional<String> memberHint(String owner, String member, ApiSurface after) {
        // A known move in the signature's types says more than "the signature changed"
        Optional<String> moved =
                KNOWN_MOVES.entrySet().stream()
                        .filter(m -> member.contains("L" + m.getKey().replace('.', '/')))
                        .map(Map.Entry::getValue)
                        .findFirst();
        String name =
                member.contains("(")
                        ? member.substring(0, member.indexOf('('))
                        : member.split(":")[0];
        ApiSurface.ApiClass now = after.classes().get(owner);
        List<String> same =
                now == null
                        ? List.of()
                        : now.members().keySet().stream()
                                .filter(m -> m.startsWith(name + "(") || m.startsWith(name + ":"))
                                .toList();
        if (same.isEmpty()) {
            return moved;
        }
        String was = member(owner, member);
        String replacement =
                same.stream()
                        .map(
                                m -> {
                                    String simple = member(owner, m);
                                    // Same short names (javax → jakarta): show the full types
                                    return (simple.equals(was) ? qualified(owner, m) : simple)
                                            .substring(dotted(owner).length() + 1);
                                })
                        .collect(Collectors.joining(" or "));
        return Optional.of(moved.map(h -> h + "; ").orElse("") + "now " + replacement);
    }

    /** Like {@link #member} but with fully qualified parameter types. */
    static String qualified(String owner, String member) {
        if (!member.contains("(")) {
            return member(owner, member);
        }
        String name = member.substring(0, member.indexOf('('));
        String arguments =
                Stream.of(Type.getArgumentTypes(member.substring(member.indexOf('('))))
                        .map(Type::getClassName)
                        .collect(Collectors.joining(", "));
        return dotted(owner) + "#" + (name.equals("<init>") ? "new" : name) + "(" + arguments + ")";
    }

    private static List<Usage> sorted(java.util.Collection<Usage> usages) {
        return usages.stream()
                .sorted(
                        Comparator.comparing(Usage::kind)
                                .thenComparing(
                                        u -> u.where().substring(0, u.where().lastIndexOf(':')))
                                .thenComparingInt(
                                        u ->
                                                Integer.parseInt(
                                                        u.where()
                                                                .substring(
                                                                        u.where().lastIndexOf(':')
                                                                                + 1))))
                .toList();
    }

    /** {@code org/example/Foo$Bar} → {@code org.example.Foo.Bar}. */
    static String dotted(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /** {@code Foo + bar(Ljava/lang/String;I)V} → {@code org.example.Foo#bar(String, int)}. */
    static String member(String owner, String member) {
        if (!member.contains("(")) {
            return dotted(owner) + "#" + member.split(":")[0];
        }
        String name = member.substring(0, member.indexOf('('));
        String arguments =
                Stream.of(Type.getArgumentTypes(member.substring(member.indexOf('('))))
                        .map(t -> t.getClassName().substring(t.getClassName().lastIndexOf('.') + 1))
                        .collect(Collectors.joining(", "));
        return dotted(owner) + "#" + (name.equals("<init>") ? "new" : name) + "(" + arguments + ")";
    }
}
