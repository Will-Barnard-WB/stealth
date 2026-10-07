package dev.stealth.core.clean;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stealth.core.DoctorReport;
import dev.stealth.core.RepoContext;
import dev.stealth.core.StealthConfig;
import dev.stealth.core.deps.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The planner against {@link MavenWorld}, so the real loader, editor and proof run offline. */
class PatchPlannerTest {

    @TempDir private Path localRepository;
    @TempDir private Path app;

    private MavenWorld world;
    private PatchPlanner planner;

    @BeforeEach
    void setUp() throws IOException {
        world = MavenWorld.create(localRepository, app);
        planner = world.planner;
    }

    @Test
    void plan_declaredVersion_editsTheLineThatSetsIt() throws Exception {
        CleanupPlan plan = plan();

        Patch direct = patchFor(plan, "com.example:direct");
        assertThat(direct.edit()).isEqualTo(new PomEdit.SetVersion("pom.xml", 12, "1.0", "1.1"));
        assertThat(direct.proof().status()).isEqualTo(Proof.Status.PROVEN);
        assertThat(direct.safe()).isTrue();
    }

    @Test
    void plan_versionManagedByTheParentsProperty_overridesThatProperty() throws Exception {
        Patch lib = safePatchFor(plan(), "com.example:lib");

        assertThat(lib.edit()).isEqualTo(new PomEdit.SetProperty("pom.xml", "lib.version", "1.1"));
        assertThat(lib.proof().status()).isEqualTo(Proof.Status.PROVEN);
        assertThat(lib.level()).isEqualTo(Versions.Update.MINOR);
    }

    @Test
    void plan_advisoryFixedOnlyInANewMajor_isASeparateUnsafePatch() throws Exception {
        Patch major =
                plan().patches().stream().filter(p -> p.crossesMajor()).findFirst().orElseThrow();

        assertThat(major.edit())
                .isEqualTo(new PomEdit.SetProperty("pom.xml", "lib.version", "2.0"));
        assertThat(major.targets()).contains("com.example:lib ADV-L2");
        assertThat(major.safe()).isFalse();
        assertThat(major.level()).isEqualTo(Versions.Update.MAJOR);
    }

    @Test
    void plan_bomMoveThatIntroducesAVulnerability_fallsBackToPinningTheArtifact() throws Exception {
        CleanupPlan plan = plan();

        assertThat(plan.patches())
                .noneMatch(
                        p ->
                                p.edit() instanceof PomEdit.SetProperty s
                                        && s.name().equals("bom.version"));
        Patch famA = patchFor(plan, "com.example:fam-a");
        assertThat(famA.edit())
                .isEqualTo(new PomEdit.PinVersion("pom.xml", "com.example", "fam-a", "1.1"));
        assertThat(famA.proof().status()).isEqualTo(Proof.Status.PROVEN);
    }

    @Test
    void plan_unmanagedTransitiveDependency_isPinned() throws Exception {
        Patch loose = patchFor(plan(), "com.example:loose");

        assertThat(loose.edit())
                .isEqualTo(new PomEdit.PinVersion("pom.xml", "com.example", "loose", "1.2"));
        assertThat(loose.proof().status()).isEqualTo(Proof.Status.PROVEN);
    }

    @Test
    void plan_safePatchesTogether_areProvenAndOnlyTheMajorOnlyAdvisoryRemains() throws Exception {
        CleanupPlan plan = plan();

        assertThat(plan.combined())
                .hasValueSatisfying(
                        c -> {
                            assertThat(c.status()).isEqualTo(Proof.Status.PROVEN);
                            assertThat(c.introduced()).isEmpty();
                        });
        assertThat(plan.remaining())
                .extracting(f -> f.advisory().orElseThrow().id())
                .containsExactly("ADV-L2");
        // Planning never touches the repository itself
        assertThat(Files.readString(app.resolve("pom.xml"))).isEqualTo(MavenWorld.APP_POM);
    }

    @Test
    void plan_noVulnerabilities_isEmpty() throws Exception {
        CleanupPlan plan =
                planner.plan(
                        new RepoContext(app, StealthConfig.defaults()),
                        new DoctorReport(List.of(), List.of()));

        assertThat(plan.patches()).isEmpty();
        assertThat(plan.combined()).isEmpty();
    }

    private CleanupPlan plan() throws Exception {
        RepoContext context = new RepoContext(app, StealthConfig.defaults());
        return planner.plan(context, world.report(context));
    }

    private static Patch patchFor(CleanupPlan plan, String dependency) {
        return plan.patches().stream()
                .filter(p -> p.changes().stream().anyMatch(c -> c.dependency().equals(dependency)))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no patch for " + dependency + ": " + plan.patches()));
    }

    private static Patch safePatchFor(CleanupPlan plan, String dependency) {
        return plan.patches().stream()
                .filter(Patch::safe)
                .filter(p -> p.changes().stream().anyMatch(c -> c.dependency().equals(dependency)))
                .findFirst()
                .orElseThrow();
    }
}
