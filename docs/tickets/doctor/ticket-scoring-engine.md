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

- [ ] Unit tests for the formula: weights, diminishing returns, bounds (0–100), incomplete categories
- [ ] Golden tests on fixtures: `boot4-clean` scores ≥ 95 on both categories, and `boot2-legacy` scores lower than `boot4-clean` in both categories (exact values snapshotted)
- [ ] `FixPlanner` groups a parent upgrade's findings into one item
- [ ] Same input always gives the same score and order (determinism test)
- [ ] ADR-002 updated if the implementation diverges
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Debt in money terms (effort-hours × rate): Phase 5
- Score history and trends: platform phase

Suggested branch: `feature/CU-<clickup-id>_scoring_engine`
Depends on: ADR-002, analyzer SPI. Best tuned once at least the freshness, vulnerability and secrets analyzers exist
