package dev.stealth.core.eol;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.maven.DeclaredVersion;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenModule;
import dev.stealth.core.maven.MavenProjectModel;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Reports Java and Spring Boot versions whose release cycle is past end of life, or close to it.
 * End of life means no more security patches, so the category is security rather than tech.
 */
public class EndOfLifeAnalyzer implements Analyzer {

    private static final String HELP =
            "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/eol.md";

    static final Rule PAST_END_OF_LIFE =
            rule(
                    "eol/past-end-of-life",
                    "Past end of life",
                    "The release cycle in use no longer gets security patches.",
                    Severity.HIGH);
    static final Rule APPROACHING_END_OF_LIFE =
            rule(
                    "eol/approaching-end-of-life",
                    "Approaching end of life",
                    "The release cycle in use stops getting security patches within six months.",
                    Severity.LOW);

    static final Period WARNING_WINDOW = Period.ofMonths(6);

    private static final String SPRING_BOOT_PRODUCT = "spring-boot";

    private final MavenModelLoader loader;
    private final EndOfLifeClient client;
    private final Clock clock;

    public EndOfLifeAnalyzer(MavenModelLoader loader, EndOfLifeClient client, Clock clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "eol";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Rule> rules() {
        return List.of(PAST_END_OF_LIFE, APPROACHING_END_OF_LIFE);
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        StealthConfig.Eol settings = context.config().eol();
        List<Subject> subjects = subjects(context.get(loader), settings);
        if (subjects.isEmpty()) {
            return List.of();
        }

        Map<String, List<ReleaseCycle>> catalogue = lookUp(subjects, settings);
        LocalDate today = LocalDate.now(clock);
        List<Finding> findings = new ArrayList<>();
        for (Subject subject : subjects) {
            List<ReleaseCycle> cycles = catalogue.get(product(subject.product(), settings));
            Optional<ReleaseCycle> current =
                    cycles.stream().filter(c -> c.cycle().equals(subject.cycle())).findFirst();
            if (current.isEmpty()) {
                continue;
            }
            ReleaseCycle cycle = current.get();
            assess(cycle.support(), today)
                    .ifPresent(
                            assessment ->
                                    findings.add(
                                            finding(
                                                    subject,
                                                    cycle,
                                                    assessment,
                                                    upgradeTarget(cycles, subject.cycle(), today),
                                                    settings)));
        }
        return findings;
    }

    /**
     * One subject per version, not per module: a Java release or Spring Boot version set once in a
     * parent POM and inherited by five modules is one finding, at the parent.
     */
    private static List<Subject> subjects(MavenProjectModel model, StealthConfig.Eol settings) {
        Map<Subject.Key, Subject> subjects = new LinkedHashMap<>();
        settings.javaVersion()
                .flatMap(version -> Subject.configured(Product.JAVA, version))
                .ifPresent(subject -> subjects.put(subject.key(), subject));
        for (MavenModule module : model.modules()) {
            if (settings.javaVersion().isEmpty()) {
                add(subjects, Product.JAVA, module.javaVersion(), module);
            }
            add(subjects, Product.SPRING_BOOT, module.springBootVersion(), module);
        }
        return List.copyOf(subjects.values());
    }

    private static void add(
            Map<Subject.Key, Subject> subjects,
            Product product,
            Optional<DeclaredVersion> version,
            MavenModule module) {
        version.flatMap(declared -> Subject.declared(product, declared, module))
                .ifPresent(subject -> subjects.putIfAbsent(subject.key(), subject));
    }

    /** One request per product, however many modules share it. */
    private Map<String, List<ReleaseCycle>> lookUp(
            List<Subject> subjects, StealthConfig.Eol settings)
            throws IOException, InterruptedException {
        Set<String> products = new LinkedHashSet<>();
        for (Subject subject : subjects) {
            products.add(product(subject.product(), settings));
        }
        Map<String, List<ReleaseCycle>> catalogue = new LinkedHashMap<>();
        for (String product : products) {
            // Reporting only some of the products would read as "the others are supported", so an
            // unknown or unreachable one fails the analyzer instead
            catalogue.put(
                    product,
                    client.cycles(product)
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "endoflife.date doesn't track " + product)));
        }
        return catalogue;
    }

    private static String product(Product product, StealthConfig.Eol settings) {
        return product == Product.JAVA ? settings.javaDistribution() : SPRING_BOOT_PRODUCT;
    }

    /** Why a cycle is worth reporting, if it is. */
    private record Assessment(Rule rule, String clause) {}

    private static Optional<Assessment> assess(Support support, LocalDate today) {
        return switch (support) {
            case Support.Until(LocalDate end) -> {
                if (today.isAfter(end)) {
                    yield Optional.of(
                            new Assessment(PAST_END_OF_LIFE, "reached end of life on " + end));
                }
                if (!today.isBefore(end.minus(WARNING_WINDOW))) {
                    yield Optional.of(
                            new Assessment(
                                    APPROACHING_END_OF_LIFE, "reaches end of life on " + end));
                }
                yield Optional.empty();
            }
            case Support.Ended ignored ->
                    Optional.of(new Assessment(PAST_END_OF_LIFE, "is past end of life"));
            case Support.Open ignored -> Optional.empty();
        };
    }

    /**
     * The smallest upgrade that gets back into support: the oldest newer cycle that isn't itself
     * past or approaching end of life, falling back to the oldest still supported one.
     */
    private static Optional<ReleaseCycle> upgradeTarget(
            List<ReleaseCycle> cycles, String current, LocalDate today) {
        ComparableVersion version = new ComparableVersion(current);
        List<ReleaseCycle> supported =
                cycles.stream()
                        .filter(c -> new ComparableVersion(c.cycle()).compareTo(version) > 0)
                        .filter(c -> !c.support().endedBy(today))
                        .sorted(Comparator.comparing(c -> new ComparableVersion(c.cycle())))
                        .toList();
        return supported.stream()
                .filter(c -> assess(c.support(), today).isEmpty())
                .findFirst()
                .or(() -> supported.stream().findFirst());
    }

    private static Finding finding(
            Subject subject,
            ReleaseCycle current,
            Assessment assessment,
            Optional<ReleaseCycle> target,
            StealthConfig.Eol settings) {
        Rule rule = assessment.rule();
        return new Finding(
                rule.id(),
                rule.category(),
                rule.defaultSeverity(),
                message(subject, current, assessment, target, settings),
                subject.location(),
                component(subject),
                Optional.empty(),
                remediation(subject, target),
                Fingerprints.of(rule.id(), subject.module(), subject.product().id()));
    }

    private static String message(
            Subject subject,
            ReleaseCycle current,
            Assessment assessment,
            Optional<ReleaseCycle> target,
            StealthConfig.Eol settings) {
        StringBuilder message = new StringBuilder();
        if (subject.product() == Product.JAVA) {
            message.append(subject.configured() ? "runs on Java " : "targets Java ")
                    .append(subject.cycle())
                    .append(" (")
                    .append(settings.javaDistribution())
                    .append(")");
        } else {
            message.append("uses ")
                    .append(subject.product().label())
                    .append(" ")
                    .append(subject.cycle());
        }
        message.append(", which ").append(assessment.clause()).append(".");
        target.ifPresent(
                cycle ->
                        message.append(" The oldest supported release is ")
                                .append(name(subject.product(), cycle))
                                .append("."));
        if (current.extendedSupport() instanceof Support.Until(LocalDate end)) {
            message.append(" Commercial support runs to ").append(end).append(".");
        }
        return message.toString();
    }

    private static String name(Product product, ReleaseCycle cycle) {
        return product.label()
                + " "
                + cycle.cycle()
                + cycle.latest().map(latest -> " (" + latest + ")").orElse("");
    }

    private static Optional<String> component(Subject subject) {
        if (subject.product() != Product.SPRING_BOOT) {
            return Optional.empty();
        }
        return Optional.of("pkg:maven/org.springframework.boot/spring-boot@" + subject.version());
    }

    private static Optional<Remediation> remediation(
            Subject subject, Optional<ReleaseCycle> target) {
        return target.map(
                cycle ->
                        new Remediation(
                                cycle.latest(),
                                Optional.of("Upgrade to " + name(subject.product(), cycle))));
    }

    private static Rule rule(String id, String name, String shortDescription, Severity severity) {
        return new Rule(
                id,
                name,
                shortDescription,
                URI.create(HELP + "#" + id.replace('/', '-')),
                Category.SECURITY,
                severity);
    }

    /**
     * One version to check.
     *
     * @param module the module directory the fingerprint is keyed on: where the version is set
     * @param configured whether it came from {@code .stealth.yml} rather than from the build
     */
    private record Subject(
            Product product,
            String version,
            String cycle,
            Location location,
            String module,
            boolean configured) {

        record Key(Product product, String cycle, Location location) {}

        static Optional<Subject> declared(
                Product product, DeclaredVersion version, MavenModule module) {
            Location location =
                    version.declaredAt()
                            .orElseGet(
                                    () ->
                                            new Location(
                                                    Optional.of(module.pomPath()),
                                                    OptionalInt.empty(),
                                                    Optional.of(module.directory())));
            return product.cycleOf(version.value())
                    .map(
                            cycle ->
                                    new Subject(
                                            product,
                                            version.value(),
                                            cycle,
                                            location,
                                            location.module().orElse(module.directory()),
                                            false));
        }

        static Optional<Subject> configured(Product product, String version) {
            return product.cycleOf(version)
                    .map(
                            cycle ->
                                    new Subject(
                                            product,
                                            version,
                                            cycle,
                                            Location.repository(),
                                            "",
                                            true));
        }

        Key key() {
            return new Key(product, cycle, location);
        }
    }
}
