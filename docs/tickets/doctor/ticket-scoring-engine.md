# Scoring engine and ranked fix list

ClickUp: _not yet created_

### What is the problem we are trying to solve?

A list of 200 findings doesn't tell anyone how healthy a repo is or what to do first. `stealth doctor` needs a single 0–100 score per category (tech, security) and overall, plus a short ranked list of the fixes that improve it most.

### Who is impacted?

CLI users, CI users gating with `--fail-under`, AI agents calling `repo_health` over MCP, and later the platform dashboard.

### Why it's important or urgent?

The score is the product's headline and becomes a public contract once teams gate CI on it. The ranked fix list is what turns the report into action, and it maps directly onto Phase 2 upgrades.

### What is the proposed solution?

Every doctor run produces a tech score, a security score and an overall score (0–100), and a "fix these first" list where each item groups the findings a single action resolves (e.g. "upgrade Spring Boot to 3.3: fixes 1 EOL, 4 CVEs, 12 outdated dependencies").

### Technical approach

- **`core`**: `ScoringEngine` implementing ADR-002 exactly, producing `HealthScore(tech, security, overall, incompleteCategories)`
- Severity weights with diminishing returns per analyzer, so one noisy analyzer can't zero the score
- Categories with a failed or timed-out analyzer are marked incomplete and shown as such, never silently scored as clean
- `FixPlanner`: groups findings by remediation (same target dependency/parent, same file for secrets) and ranks groups by score gained, severity as the tie-breaker. Deterministic ordering
- Findings suppressed or downgraded by `.stealth.yml` are applied **before** scoring
- Score and fix list live on `DoctorReport` for renderers and MCP

### Definition of done

- [x] Unit tests for the formula: weights, diminishing returns, bounds (0–100), incomplete categories
- [x] Golden tests on fixtures: `boot4-clean` scores ≥ 95 on both categories, and `boot2-legacy` scores lower than `boot4-clean` in both categories (exact values snapshotted)
- [x] `FixPlanner` groups a parent upgrade's findings into one item
- [x] Same input always gives the same score and order (determinism test)
- [x] ADR-002 updated if the implementation diverges
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **`core/score`:** `ScoringEngine` (ADR-0002 exactly, `scoringVersion` 1), `HealthScore` (category scores with per-analyzer deductions, status, critical cap), `FixPlanner`. `DoctorReport.score()` and `.fixes()` expose them to renderers and, later, MCP.
- **Incomplete vs not run:** a failed or timed-out analyzer marks its category *incomplete* (shown on the category and Health lines). A category with no analyzer run at all (e.g. `doctor --secrets`) is *not run*, and there's no overall score, since the formula needs both.
- **Grouping:** dependency findings at the same `pom.xml` line are one fix (transitive vulnerabilities sit under the direct dependency or parent); findings with no component are a fix each. Replaces the CLI's `FixList`.
- **Ranking divergence (ADR updated):** points gained are computed *without* the critical ceiling. With it, every fix but the last critical one gains 0 while a critical remains, so the order fell to tie-breakers (commons-lang3 ranked above guava on boot2-legacy). Points aren't shown in the terminal for the same reason.
- **Severity filters** (`--critical`, …) now only narrow what's listed; the score always counts every finding. Before, the report was filtered first.
- **Golden values at the reference date:** boot4-clean 100/100/100. boot2-legacy security **50** (vulnerabilities 40 capped + EOL 8 = 52, critical ceiling), tech **91** (deps 6.2, hygiene 1.7, maintenance 1.0), overall **50**. Tech 91 (an A) is generous for a neglected repo: flagged in the ADR's "Expected to change" for tuning before the launch freeze.

### Out of scope

- Debt in money terms (effort-hours × rate): Phase 5
- Score history and trends: platform phase

Suggested branch: `feature/CU-<clickup-id>_scoring_engine`
Depends on: ADR-002, analyzer SPI. Best tuned once at least the freshness, vulnerability and secrets analyzers exist
