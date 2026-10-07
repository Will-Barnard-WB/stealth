# stealth clean, step 1: proven dependency patches

ClickUp: _not yet created_ · Proposal: [docs/direction/clean-and-hooks.md](../../direction/clean-and-hooks.md) (delivery step 1)

### What is the problem we are trying to solve?

`doctor` and `repo_health` say *what* is vulnerable, but not what can be fixed now. In a Spring Boot 2.x app most
CVEs come in transitively through Spring Boot, the scanner's advice is "upgrade to Boot 3", and agents asked to fix
them hand-edit `pom.xml` with versions they can't check against the resolved tree.

### What is the proposed solution?

`stealth clean` (CLI) and `plan_cleanup` / `apply_cleanup` / `verify_cleanup` (MCP): work out the version changes that
clear vulnerabilities without a framework upgrade, prove each on the re-resolved dependency tree, apply the safe ones
on a new branch with the tests as a gate, and verify changes against a base commit.

### Technical approach

- **`PatchPlanner`** (core `dev.stealth.core.clean`): per vulnerable dependency, the cheapest edit: its declared
  `<version>` line; else the property an inherited parent manages it with, or the version property of the BOM that
  parent imports (whole family moves together); else a `<dependencyManagement>` pin. Same-edit candidates merge.
  Three tiers per dependency: a patch release within the current line (safe), a minor jump past what the framework
  manages (needs review), a new major (major).
- **Proof:** each patch is applied (`PomEditor`, text edits) to a scratch copy of the POMs, re-resolved with
  `MavenModelLoader` and re-checked with `VulnerabilityAnalyzer`. Rejected if it introduces a vulnerability, adds
  resolution warnings, loses a dependency tree, or the target version doesn't resolve. Only published fixed versions
  are planned (`MavenModelLoader.isPublished`).
- **Model:** `MavenModule.managedVersions` records, per managed version, the managing model, its property, the BOM it
  comes from, and whether this repository can override it.
- **`Cleaner`:** clean-tree check, `git worktree add -b stealth/clean-<time>`, baseline tests (`TestRunner`: mvnw,
  mvn or `--test-command`; Surefire reports), one commit per patch, tests again, patch-by-patch isolation on failure,
  re-check, worktree removed, branch kept only if something was committed.
- **`CleanupVerifier`:** doctor on the working tree and on a base commit (temporary worktree), compared by fingerprint.
- **CLI** `CleanCommand` / `CleanReport`; **JSON** `CleanupJson`, shared with the MCP `CleanupTools`.

### Definition of done

- [x] Unit tests: `PomEditorTest`, `PatchPlannerTest` (each strategy and tier, BOM fallback, unpublished fixes),
  `CleanerTest` (real git: branch, isolation of a breaking patch, failing baseline, dirty tree, not git),
  `CleanupVerifierTest`; loader tests for managed versions on the fixtures. Offline, against `MavenWorld`, a small
  Maven world written to disk.
- [x] MCP: `McpServerIT` lists and calls the new tools.
- [x] Measured on `boot2-legacy` with live data: 47 of 108 cleared by safe patches, 76 with reviewed minor jumps, tests
  green, 13 seconds.
- [x] Verified in Claude Code (headless): asked to "fix what you can without upgrading Spring Boot", it called
  `plan_cleanup` then `apply_cleanup` and reported the branch, what's left and the review items.
- [ ] Measured on 3–4 public Spring Boot 2.7 repositories.
- [x] `./mvnw verify` passes.

### Notes from implementation

- **The first live run found a hole in the proof:** OSV lists Spring 5.3.42 as the fix, but 5.3.40+ are commercial
  releases not on Maven Central, and the resolver quietly skips POMs it can't find, so the CVEs looked cleared. The
  test gate caught it; now only published versions are planned and unresolvable trees are rejected.
- **Same-major isn't safe enough for framework-managed versions:** Logback 1.2 → 1.5 passed `boot2-legacy`'s tests but
  breaks Spring Boot 2.7 at startup. Hence the review tier, and the patch-line tier (Logback 1.2.13), which Claude
  Code itself suggested after reviewing the plan.
- **Agents need to be told:** without guidance, Claude Code hand-edited `pom.xml` (snakeyaml 2.2, guava 33, unproven).
  The server instructions and the `repo_health` / `list_findings` hints now point at `plan_cleanup`.
- **Thin tests are the remaining risk:** `boot2-legacy`'s only test never starts Spring, so "tests pass" says little.
  That's what step 4 (`test_gaps`) is for.

### Out of scope (later steps)

Hooks (step 2), `upgrade_impact` (step 3), `test_gaps` / `verify_tests` (step 4), OpenRewrite migrations and
`--open-pr` (step 5).
