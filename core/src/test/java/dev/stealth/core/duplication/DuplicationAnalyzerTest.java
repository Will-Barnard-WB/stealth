package dev.stealth.core.duplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stealth.core.Category;
import dev.stealth.core.Finding;
import dev.stealth.core.Fixture;
import dev.stealth.core.Location;
import dev.stealth.core.RepoContext;
import dev.stealth.core.Severity;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.duplication.DuplicationAnalyzer.Block;
import dev.stealth.core.duplication.DuplicationAnalyzer.Copy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DuplicationAnalyzerTest {

    private static final String INVOICE =
            "src/main/java/com/example/dup/invoice/InvoiceSummary.java";
    private static final String REPORT = "src/main/java/com/example/dup/report/ReportSummary.java";

    @TempDir private Path tempDir;

    private final DuplicationAnalyzer analyzer = new DuplicationAnalyzer();

    @Test
    void analyze_duplicated_flagsExactlyTheCopyPastedSummary() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.DUPLICATED.context());

        assertThat(findings).hasSize(1);
        Finding finding = findings.getFirst();
        assertThat(finding.ruleId()).isEqualTo("duplication/cpd");
        assertThat(finding.category()).isEqualTo(Category.TECH);
        assertThat(finding.severity()).isEqualTo(Severity.LOW);
        assertThat(finding.location()).isEqualTo(location(INVOICE, 10, ""));
        assertThat(finding.relatedLocations()).containsExactly(location(REPORT, 10, ""));
        assertThat(finding.message())
                .isEqualTo(
                        "28 lines (236 tokens) at lines 10-37 are duplicated at "
                                + REPORT
                                + ":10-37.");
        assertThat(finding.remediation().flatMap(r -> r.description())).isPresent();
    }

    /** Below the default threshold (text/*Check.java), and renamed (export/ExportSummary.java). */
    @Test
    void analyze_duplicated_doesNotFlagSmallOrRenamedCopies() throws Exception {
        List<Finding> findings = analyzer.analyze(Fixture.DUPLICATED.context());

        assertThat(findings)
                .flatMap(DuplicationAnalyzerTest::paths)
                .noneMatch(path -> path.contains("/text/") || path.contains("/export/"));
    }

    @Test
    void analyze_thresholdAtTheSmallHelpersSize_flagsThemToo() throws Exception {
        List<Finding> findings =
                new DuplicationAnalyzer(29, false).analyze(Fixture.DUPLICATED.context());

        assertThat(findings)
                .extracting(f -> f.location().path().orElseThrow())
                .containsExactly(INVOICE, "src/main/java/com/example/dup/text/NameCheck.java");
    }

    @Test
    void analyze_thresholdJustAboveTheSmallHelpersSize_doesNotFlagThem() throws Exception {
        assertThat(new DuplicationAnalyzer(30, false).analyze(Fixture.DUPLICATED.context()))
                .hasSize(1);
    }

    @Test
    void analyze_thresholdAboveTheSummarysSize_findsNothing() throws Exception {
        assertThat(new DuplicationAnalyzer(237, false).analyze(Fixture.DUPLICATED.context()))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Fixture.class, names = "DUPLICATED", mode = EnumSource.Mode.EXCLUDE)
    void analyze_otherFixtures_findsNothing(Fixture fixture) throws Exception {
        assertThat(analyzer.analyze(fixture.context())).isEmpty();
    }

    @Test
    void analyze_copyOutsideGit_findsTheSameDuplication() throws Exception {
        Path copy = Fixture.DUPLICATED.copyTo(tempDir);

        assertThat(analyzer.analyze(context(copy)))
                .isEqualTo(analyzer.analyze(Fixture.DUPLICATED.context()));
    }

    @Test
    void analyze_generatedSources_areNotScanned() throws Exception {
        Path copy = Fixture.DUPLICATED.copyTo(tempDir);
        copyFile(copy, INVOICE, "target/generated-sources/annotations/com/example/Gen.java");
        copyFile(copy, INVOICE, "generated-sources/src/main/java/com/example/Gen.java");
        copyFile(copy, INVOICE, "src/main/resources/InvoiceSummary.java.txt");

        List<Finding> findings = analyzer.analyze(context(copy));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().relatedLocations()).hasSize(1);
    }

    @Test
    void isSource_packageNamedLikeBuildOutput_isTrue() {
        assertThat(analyzer.isSource("src/main/java/com/acme/target/Targeting.java")).isTrue();
    }

    @Test
    void analyze_testSources_areScannedOnlyWhenIncluded() throws Exception {
        Path copy = Fixture.DUPLICATED.copyTo(tempDir);
        String test = "src/test/java/com/example/dup/SummaryFixture.java";
        copyFile(copy, INVOICE, test);

        List<Finding> withoutTests = analyzer.analyze(context(copy));
        List<Finding> withTests =
                new DuplicationAnalyzer(DuplicationAnalyzer.DEFAULT_MIN_TOKENS, true)
                        .analyze(context(copy));

        assertThat(withoutTests.getFirst().relatedLocations()).hasSize(1);
        assertThat(withTests).hasSize(1);
        assertThat(withTests.getFirst().relatedLocations())
                .containsExactly(location(REPORT, 10, ""), location(test, 10, ""));
        assertThat(withTests.getFirst().severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void analyze_copiesInDifferentModules_recordsEachCopysModule() throws Exception {
        String source = Fixture.DUPLICATED.path().resolve(INVOICE).toString();
        write(tempDir, "api/src/main/java/com/example/A.java", Files.readString(Path.of(source)));
        write(
                tempDir,
                "app/core/src/main/java/com/example/B.java",
                Files.readString(Path.of(source)));

        Finding finding = analyzer.analyze(context(tempDir)).getFirst();

        assertThat(finding.location().module()).contains("api");
        assertThat(finding.relatedLocations())
                .extracting(Location::module)
                .containsExactly(Optional.of("app/core"));
    }

    /** Each generated statement is about 6 CPD tokens. */
    @Test
    void analyze_largeBlock_isMedium() throws Exception {
        String longClass = longClass(DuplicationAnalyzer.MEDIUM_TOKENS / 5);
        write(tempDir, "src/main/java/a/First.java", longClass);
        write(tempDir, "src/main/java/b/Second.java", longClass);

        Finding finding = analyzer.analyze(context(tempDir)).getFirst();

        assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void analyze_longButCommentedBlock_isLow() throws Exception {
        String source = Files.readString(Fixture.DUPLICATED.path().resolve(INVOICE));
        String commented =
                source.replace(
                        "        for (", "        // comment\n".repeat(40) + "        for (");
        write(tempDir, "src/main/java/a/Summary.java", commented);
        write(tempDir, "src/main/java/b/Summary.java", commented);

        Finding finding = analyzer.analyze(context(tempDir)).getFirst();

        assertThat(finding.message()).startsWith("68 lines");
        assertThat(finding.severity()).isEqualTo(Severity.LOW);
    }

    @Test
    void analyze_manyCopies_listsThreeAndCountsTheRest() throws Exception {
        String source = Files.readString(Fixture.DUPLICATED.path().resolve(INVOICE));
        for (char name = 'a'; name <= 'e'; name++) {
            write(tempDir, "src/main/java/" + name + "/Summary.java", source);
        }

        Finding finding = analyzer.analyze(context(tempDir)).getFirst();

        assertThat(finding.relatedLocations()).hasSize(4);
        assertThat(finding.message())
                .endsWith(
                        "at lines 10-37 are duplicated at"
                                + " src/main/java/b/Summary.java:10-37,"
                                + " src/main/java/c/Summary.java:10-37,"
                                + " src/main/java/d/Summary.java:10-37 and 1 more place.");
    }

    @Test
    void analyze_blockMovesDown_keepsItsFingerprint() throws Exception {
        Path copy = Fixture.DUPLICATED.copyTo(tempDir);
        Finding before = analyzer.analyze(context(copy)).getFirst();
        for (String path : List.of(INVOICE, REPORT)) {
            Path file = copy.resolve(path);
            Files.writeString(
                    file, Files.readString(file).replace("public class", "\n\npublic class"));
        }

        Finding after = analyzer.analyze(context(copy)).getFirst();

        assertThat(after.location().line()).hasValue(12);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
    }

    @Test
    void analyze_duplicatedCodeChanges_changesItsFingerprint() throws Exception {
        Path copy = Fixture.DUPLICATED.copyTo(tempDir);
        Finding before = analyzer.analyze(context(copy)).getFirst();
        for (String path : List.of(INVOICE, REPORT)) {
            Path file = copy.resolve(path);
            Files.writeString(file, Files.readString(file).replace("largest", "maximum"));
        }

        Finding after = analyzer.analyze(context(copy)).getFirst();

        assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
    }

    @Test
    void analyze_noJavaSources_findsNothing() throws Exception {
        write(tempDir, "README.md", "# nothing here");

        assertThat(analyzer.analyze(context(tempDir))).isEmpty();
    }

    @Test
    void merged_matchesSharingACopy_areOneBlockWithTheLargerMatchsSize() {
        Block ab = block(30, 200, "hash-ab", copy("A.java", 10, 40), copy("B.java", 5, 35));
        Block ac = block(32, 210, "hash-ac", copy("A.java", 8, 40), copy("C.java", 1, 32));
        Block de = block(20, 120, "hash-de", copy("D.java", 1, 20), copy("E.java", 1, 20));

        List<Block> blocks = DuplicationAnalyzer.merged(List.of(ab, de, ac));

        assertThat(blocks)
                .containsExactlyInAnyOrder(
                        block(
                                32,
                                210,
                                "hash-ac",
                                copy("A.java", 8, 40),
                                copy("B.java", 5, 35),
                                copy("C.java", 1, 32)),
                        de);
    }

    @Test
    void merged_matchesInSeparateFiles_stayApart() {
        Block ab = block(30, 200, "ab", copy("A.java", 10, 40), copy("B.java", 5, 35));
        Block ab2 = block(25, 150, "ab2", copy("A.java", 60, 85), copy("B.java", 70, 95));

        assertThat(DuplicationAnalyzer.merged(List.of(ab, ab2))).containsExactly(ab, ab2);
    }

    @Test
    void new_nonPositiveThreshold_throws() {
        assertThatThrownBy(() -> new DuplicationAnalyzer(0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> paths(Finding finding) {
        return Stream.concat(Stream.of(finding.location()), finding.relatedLocations().stream())
                .map(l -> l.path().orElseThrow())
                .toList();
    }

    private static Block block(int lines, int tokens, String hash, Copy... copies) {
        return new Block(List.of(copies), lines, tokens, hash);
    }

    private static Copy copy(String path, int startLine, int endLine) {
        return new Copy(path, startLine, endLine);
    }

    private static Location location(String path, int line, String module) {
        return new Location(Optional.of(path), OptionalInt.of(line), Optional.of(module));
    }

    /**
     * A class whose one method is {@code statements} lines long. Each line uses its own variables:
     * lines differing only in literals would count as copies of each other.
     */
    private static String longClass(int statements) {
        StringBuilder source = new StringBuilder("class Long {\n    int sum(int v0) {\n");
        for (int i = 1; i <= statements; i++) {
            source.append("        int v%d = v%d * 31;\n".formatted(i, i - 1));
        }
        return source.append("        return v%d;\n    }\n}\n".formatted(statements)).toString();
    }

    private static RepoContext context(Path root) {
        return new RepoContext(root, StealthConfig.defaults());
    }

    private static void copyFile(Path root, String from, String to) throws IOException {
        write(root, to, Files.readString(root.resolve(from)));
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
