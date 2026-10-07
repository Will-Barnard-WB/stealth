# stealth clean, step 4: test gaps

ClickUp: _not yet created_ · Proposal: [docs/direction/clean-and-hooks.md](../../direction/clean-and-hooks.md) (delivery step 4)

### What is the problem we are trying to solve?

`apply_cleanup` and any upgrade lean on "the tests pass", but legacy repositories are thin on tests exactly where an
upgrade changes things. In `boot2-legacy` the build was green while nothing tested the one class Spring 6 breaks;
step 1's Logback 1.5 patch also passed tests that never started Spring.

### What is the proposed solution?

`stealth clean --gaps group:artifact[:version]` (MCP `test_gaps`): which places that use a dependency, or that its
upgrade breaks, no test executes; with the method to test, the existing test class, and the repository's test style.
The agent writes tests that pin current behaviour, and calls it again until there are no gaps.

### Technical approach (core `dev.stealth.core.impact`)

- **`TestGaps`** runs the tests with JaCoCo added on the command line (`org.jacoco:jacoco-maven-plugin:0.8.15`
  `prepare-agent`, `test`, `report`, with `-Dmaven.test.failure.ignore=true` so coverage is reported even when tests
  fail); pass/fail comes from the Surefire reports, as in `TestRunner`. The run compiles the code, so the call sites
  that follow are exact.
- **Sites:** with a version, `UpgradeImpact`'s usages in main code; without, every reference the compiled main code
  makes to the dependency's classes (`ApiSurface` of the jar it resolves now). `UsageScanner` now records each
  reference's enclosing method.
- **`Coverage`** reads each module's `target/site/jacoco/jacoco.xml` (lines with code, lines executed).
- **Test style:** from test dependencies (JUnit 4 or 5, Mockito, AssertJ via spring-boot-starter-test) and annotations
  in the tests (`@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, `@MockBean`, `@MockitoBean`).

### Definition of done

- [x] `TestGapsTest`, offline on every OS: the compiled library world from `UpgradeImpactTest` (helpers now in
  `CompiledWorld`) with a stand-in test run that writes a JaCoCo report: upgrade sites and plain usage sites, the
  method and existing test class, a run that writes no report.
- [x] CLI `--gaps`, MCP `test_gaps` (listed in `McpServerIT`).
- [x] Real run on `boot2-legacy` with Maven and JaCoCo: spring-web 6.1.14 has one breaking site, untested; commons-text's
  one site is covered. About 16 s.
- [x] Headless Claude Code, asked to make sure the code a spring-web 6.1.14 upgrade affects is tested before upgrading:
  called `test_gaps`, wrote `RequestIdFilterTest` in the repo's style, found its own wrong assumption through a
  failing test, fixed it, and called `test_gaps` again: no gaps, suite passing.
- [x] `./mvnw verify` passes.

### Notes from implementation

- **One tool instead of two:** the proposal had `test_gaps` and `verify_tests`. Calling `test_gaps` again reports
  coverage and test results, which is all `verify_tests` would have done.
- **It runs in the working tree:** the agent's new tests are uncommitted, so the run happens where they are; it only
  writes build output (`target/`).

### Not done

- Gradle; projects whose Surefire `argLine` drops JaCoCo's agent are detected (no report) but not fixed.
- Branch coverage: a site counts as tested when its line ran.
