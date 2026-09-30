package dev.stealth.core.duplication;

import dev.stealth.core.Analyzer;
import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fingerprints;
import dev.stealth.core.Location;
import dev.stealth.core.Remediation;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Rule;
import dev.stealth.core.Severity;
import dev.stealth.core.git.WorkingTreeFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CPDListener;
import net.sourceforge.pmd.cpd.CPDReport;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.util.log.PmdReporter;

/**
 * Reports copy-pasted Java code with PMD's copy-paste detector (CPD), run in-process. One finding
 * per block of duplicated code: its first copy is the location, the other copies are related
 * locations.
 */
public class DuplicationAnalyzer implements Analyzer {

    static final Rule DUPLICATED_CODE =
            new Rule(
                    "duplication/cpd",
                    "Duplicated code",
                    "The same block of code is copy-pasted in more than one place.",
                    URI.create(
                            "https://github.com/Will-Barnard-WB/stealth/blob/main/docs/rules/duplication.md#duplication-cpd"),
                    Category.TECH,
                    Severity.LOW);

    /** CPD's own default. */
    public static final int DEFAULT_MIN_TOKENS = 100;

    /**
     * A block this big, or copied this many times, is medium rather than low. Measured in tokens,
     * not lines, so Javadoc and blank lines don't count: about 50 lines of code.
     */
    static final int MEDIUM_TOKENS = 400;

    static final int MEDIUM_COPIES = 3;

    /** Other copies named in the message; the rest are counted. */
    private static final int COPIES_LISTED = 3;

    private static final String MAIN_SOURCES = "src/main/java/";
    private static final String TEST_SOURCES = "src/test/java/";

    private static final Set<String> GENERATED_DIRECTORIES =
            Set.of("target", "generated-sources", "generated-test-sources");

    private static final String ADVICE =
            "Extract the shared code into one method or class and call it from each place,"
                    + " so a fix only has to be made once.";

    private final int minTokens;
    private final boolean includeTests;

    public DuplicationAnalyzer() {
        this(DEFAULT_MIN_TOKENS, false);
    }

    /**
     * @param minTokens the smallest duplicated block reported, in tokens
     */
    public DuplicationAnalyzer(int minTokens, boolean includeTests) {
        if (minTokens < 1) {
            throw new IllegalArgumentException("minTokens must be positive: " + minTokens);
        }
        this.minTokens = minTokens;
        this.includeTests = includeTests;
    }

    @Override
    public String id() {
        return "duplication";
    }

    @Override
    public Category category() {
        return Category.TECH;
    }

    @Override
    public List<Rule> rules() {
        return List.of(DUPLICATED_CODE);
    }

    @Override
    public List<Finding> analyze(RepoContext context) throws Exception {
        List<String> paths =
                context.get(WorkingTreeFiles.FILES).stream().filter(this::isSource).toList();
        if (paths.isEmpty()) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>();
        try (CpdAnalysis cpd = CpdAnalysis.create(configuration())) {
            cpd.setCpdListener(new Cancellation());
            for (String path : paths) {
                Optional<String> source = read(context.root().resolve(path));
                source.ifPresent(
                        s -> cpd.files().addSourceFile(FileId.fromPathLikeString(path), s));
            }
            cpd.performAnalysis(
                    report -> report.getMatches().forEach(m -> blocks.add(Block.of(m, report))));
        } catch (CancellationException e) {
            var interrupted = new InterruptedException("duplication analysis cancelled");
            interrupted.initCause(e);
            throw interrupted;
        }
        return merged(blocks).stream()
                .map(DuplicationAnalyzer::finding)
                .sorted(
                        Comparator.comparing((Finding f) -> f.location().path().orElse(""))
                                .thenComparingInt(f -> f.location().line().orElse(0)))
                .toList();
    }

    private CPDConfiguration configuration() {
        CPDConfiguration config = new CPDConfiguration();
        config.setOnlyRecognizeLanguage(config.getLanguageRegistry().getLanguageById("java"));
        config.setMinimumTileSize(minTokens);
        config.setIgnoreLiterals(true);
        config.setIgnoreAnnotations(true);
        config.setIgnoreIdentifiers(false);
        config.setSourceEncoding(StandardCharsets.UTF_8);
        // A file CPD can't tokenize is skipped, so it can't hide findings in the files it can
        config.setReporter(PmdReporter.quiet());
        return config;
    }

    /** Hand-written Java in a module's source root, outside build output and generated code. */
    boolean isSource(String path) {
        if (!path.endsWith(".java")) {
            return false;
        }
        int root = sourceRoot(path, MAIN_SOURCES);
        if (root < 0 && includeTests) {
            root = sourceRoot(path, TEST_SOURCES);
        }
        return root >= 0
                && Arrays.stream(path.substring(0, root).split("/"))
                        .noneMatch(GENERATED_DIRECTORIES::contains);
    }

    /** Where {@code root} starts in {@code path}, at the start or after a {@code /}; else -1. */
    private static int sourceRoot(String path, String root) {
        if (path.startsWith(root)) {
            return 0;
        }
        int index = path.indexOf("/" + root);
        return index < 0 ? -1 : index + 1;
    }

    /** The module directory a source file belongs to: whatever comes before its source root. */
    private static String module(String path) {
        int index = sourceRoot(path, MAIN_SOURCES);
        if (index < 0) {
            index = Math.max(0, sourceRoot(path, TEST_SOURCES));
        }
        return index == 0 ? "" : path.substring(0, index - 1);
    }

    /** The file as text, or empty if it's gone since it was listed. */
    private static Optional<String> read(Path file) throws IOException {
        try {
            return Optional.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /**
     * CPD reports one match per exact token run, so when three copies have drifted slightly, A–B
     * and A–C can come back as two matches over the same code. Matches sharing any lines are one
     * block of duplicated code, and one finding.
     */
    static List<Block> merged(List<Block> matches) {
        List<Block> sorted =
                matches.stream()
                        .sorted(
                                Comparator.comparingInt(Block::tokens)
                                        .reversed()
                                        .thenComparing(b -> b.copies().getFirst().path())
                                        .thenComparingInt(b -> b.copies().getFirst().startLine()))
                        .toList();
        List<Block> blocks = new ArrayList<>();
        for (Block match : sorted) {
            Block block = match;
            boolean merging = true;
            while (merging) {
                merging = false;
                for (int i = 0; i < blocks.size(); i++) {
                    if (blocks.get(i).overlaps(block)) {
                        block = blocks.remove(i).merge(block);
                        merging = true;
                        break;
                    }
                }
            }
            blocks.add(block);
        }
        return blocks.stream().filter(b -> b.copies().size() > 1).toList();
    }

    private static Finding finding(Block block) {
        List<Location> locations = block.copies().stream().map(Copy::location).toList();
        Severity severity =
                block.tokens() >= MEDIUM_TOKENS || block.copies().size() >= MEDIUM_COPIES
                        ? Severity.MEDIUM
                        : Severity.LOW;
        List<String> keys = new ArrayList<>(block.copies().stream().map(Copy::path).toList());
        keys.add(block.codeHash());
        return new Finding(
                DUPLICATED_CODE.id(),
                DUPLICATED_CODE.category(),
                severity,
                message(block),
                locations.getFirst(),
                locations.subList(1, locations.size()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Remediation(Optional.empty(), Optional.of(ADVICE))),
                Fingerprints.of(DUPLICATED_CODE.id(), keys.toArray(String[]::new)));
    }

    /**
     * For example: {@code 28 lines (236 tokens) at lines 10-37 are duplicated at
     * src/main/java/com/example/ReportSummary.java:10-37.}
     */
    private static String message(Block block) {
        List<Copy> others = block.copies().subList(1, block.copies().size());
        String listed =
                others.stream()
                        .limit(COPIES_LISTED)
                        .map(c -> c.path() + ":" + c.range())
                        .collect(Collectors.joining(", "));
        int unlisted = others.size() - COPIES_LISTED;
        if (unlisted > 0) {
            listed += " and " + unlisted + " more " + (unlisted == 1 ? "place" : "places");
        }
        return block.lines()
                + " lines ("
                + block.tokens()
                + " tokens) at lines "
                + block.copies().getFirst().range()
                + " are duplicated at "
                + listed
                + ".";
    }

    record Copy(String path, int startLine, int endLine) {

        static Copy of(Mark mark) {
            return new Copy(
                    mark.getLocation().getFileId().getOriginalPath(),
                    mark.getLocation().getStartLine(),
                    mark.getLocation().getEndLine());
        }

        boolean overlaps(Copy other) {
            return path.equals(other.path)
                    && startLine <= other.endLine
                    && other.startLine <= endLine;
        }

        Copy union(Copy other) {
            return new Copy(
                    path, Math.min(startLine, other.startLine), Math.max(endLine, other.endLine));
        }

        String range() {
            return startLine + "-" + endLine;
        }

        Location location() {
            return new Location(
                    Optional.of(path), OptionalInt.of(startLine), Optional.of(module(path)));
        }
    }

    /**
     * Duplicated code and every place it's copied to, sorted by path and line.
     *
     * @param lines the size of the largest CPD match in the block
     * @param tokens the same match's token count
     * @param codeHash that match's first copy without whitespace, hashed (ADR-0001): reformatting
     *     or moving the block keeps the fingerprint, editing the duplicated code changes it
     */
    record Block(List<Copy> copies, int lines, int tokens, String codeHash) {

        static final Comparator<Copy> ORDER =
                Comparator.comparing(Copy::path).thenComparingInt(Copy::startLine);

        Block {
            copies = copies.stream().sorted(ORDER).toList();
        }

        static Block of(Match match, CPDReport report) {
            List<Mark> marks =
                    match.getMarkSet().stream()
                            .sorted(Comparator.comparing(Copy::of, ORDER))
                            .toList();
            List<Copy> copies = new ArrayList<>();
            marks.forEach(mark -> add(copies, Copy.of(mark)));
            String code =
                    report.getSourceCodeSlice(marks.getFirst()).toString().replaceAll("\\s+", "");
            return new Block(copies, match.getLineCount(), match.getTokenCount(), sha256(code));
        }

        boolean overlaps(Block other) {
            return copies.stream().anyMatch(c -> other.copies.stream().anyMatch(c::overlaps));
        }

        /** Both blocks' copies; sizes and hash from the larger, which is {@code this} on a tie. */
        Block merge(Block other) {
            List<Copy> union = new ArrayList<>(copies);
            other.copies.forEach(copy -> add(union, copy));
            Block larger = other.tokens > tokens ? other : this;
            return new Block(union, larger.lines, larger.tokens, larger.codeHash);
        }

        /** Adds {@code copy}, widening any copy it overlaps instead of listing the code twice. */
        private static void add(List<Copy> copies, Copy copy) {
            Copy widened = copy;
            boolean widening = true;
            while (widening) {
                widening = false;
                for (int i = 0; i < copies.size(); i++) {
                    if (copies.get(i).overlaps(widened)) {
                        widened = copies.remove(i).union(widened);
                        widening = true;
                        break;
                    }
                }
            }
            copies.add(widened);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }

    /**
     * Stops CPD when the runner's timeout interrupts this thread. CPD doesn't check interruption
     * itself, but it reports progress after every file and between phases.
     */
    private static final class Cancellation implements CPDListener {

        @Override
        public void addedFile(int fileCount) {
            checkInterrupted();
        }

        @Override
        public void phaseUpdate(int phase) {
            checkInterrupted();
        }

        private static void checkInterrupted() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }
    }
}
