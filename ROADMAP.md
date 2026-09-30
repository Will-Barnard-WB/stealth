# Roadmap

stealth is an autonomous maintainer that finds, prevents and clears tech debt and security debt. It's built in phases.
Each phase ships something usable on its own.

| Phase | Ships | Timeline | Proves |
|---|---|---|---|
| 0 — Foundations | Scaffold, CI, fixtures, docs | Weeks 1–2 | — |
| 1 — Doctor + MCP | `stealth doctor` + MCP server | Weeks 3–8 | People install it and find it useful |
| 2 — Upgrade | Test-verified migrations and upgrades | Weeks 9–14 | It saves real time |
| 3 — Conventions + Hooks | `learn`, `check`, Claude Code hook | Weeks 15–20 | It prevents new debt from AI agents |
| 4 — Debt Budget | GitHub Action with AI vs human debt | Weeks 21–24 | Value at team level |
| 5 — Platform | Hosted fleet dashboard, campaigns | Months 6–9 | Revenue |
| 6 — Prune | Runtime-evidence removal of unused code | Months 9–12 | Security budget, bigger deals |

Tasks are tracked in ClickUp (folder "Stealth — Autonomous Maintainer"). Tasks marked still need to be added there.

## Phase 0 — Foundations
- [ ] **Check name availability:** GitHub org, Maven Central groupId, domain, Homebrew tap. `stealth` is a placeholder.
- [x] **Maven multi-module scaffold:** parent POM (Java 21), modules `core`, `cli` (picocli), `mcp`. JUnit 5, AssertJ, Spotless.
- [x] **CI and release pipeline:** GitHub Actions for build, test and format check. JReleaser → GitHub Releases + Homebrew tap.
- [x] **Fixture repos:** `boot2-legacy`, `boot4-clean`, `with-secrets`, `duplicated`, `multi-module`. Every analyzer gets precision tests on these.
- [x] **License and community docs:** Apache-2.0 license, README, CONTRIBUTING, SECURITY.md, open-core boundary doc.
- [x] **Design ADRs:** Finding model, scoring formula, SARIF mapping, `.stealth.yml` format. See [docs/adr](docs/adr/README.md).

## Phase 1 — Doctor + MCP
- [x] **Analyzer SPI and parallel runner:** `Analyzer.analyze(RepoContext) -> List<Finding>`, run in parallel with timeouts. ([ADR-0001](docs/adr/0001-finding-model.md))
- [x] **Maven model loading:** effective versions including parent POMs and BOMs (maven-model-builder), multi-module support.
- [x] **Dependency freshness analyzer:** latest versions from Maven Central's `maven-metadata.xml`, cached in `~/.stealth/cache`. Rules in [docs/rules/deps.md](docs/rules/deps.md).
- [ ] **Maintenance analyzer:** flag dependencies with no release in over 2 years.
- [ ] **Vulnerability analyzer:** OSV.dev `querybatch`, recording the fixed version.
- [ ] **Framework and runtime end-of-life analyzer:** Java and Spring Boot versions against endoflife.date.
- [ ] **Secrets analyzer:** regex + entropy ruleset with an allowlist (working tree first, git history later).
- [ ] **Duplication analyzer:** PMD CPD as a library.
- [ ] **Repo hygiene analyzer:** JGit for stale branches and large files; missing CODEOWNERS, CI, tests.
- [ ] **Scoring engine:** 0–100 per category (tech, security) and overall, plus a ranked fix list. ([ADR-0002](docs/adr/0002-scoring-formula.md))
- [ ] **Output renderers and CI gate:** terminal, `--json`, SARIF 2.1.0, `--fail-under N`. ([ADR-0003](docs/adr/0003-sarif-mapping.md))
- [ ] **`.stealth.yml` config:** ignored paths, severity overrides, allowlists. ([ADR-0004](docs/adr/0004-stealth-yml-format.md))
- [ ] **MCP server:** stdio. Tools `repo_health`, `list_findings`, `check_dependency`.
- [ ] **Phase 1 launch:** docs, demo GIF, Show HN, r/java, Spring community. Check findings by hand on spring-petclinic first.

## Phase 2 — Upgrade
- [ ] **OpenRewrite recipe license audit** ⚠️ *do first.* Some recipe modules use Moderne's source-available license, which restricts competing commercial use.
- [ ] **OpenRewrite integration:** Spring Boot 3, Java 17/21, Jakarta EE, JUnit 5 recipes, run programmatically.
- [ ] **Recipe catalog:** `stealth upgrade --list` maps doctor findings to available fixes.
- [ ] **Git safety rules:** refuse a dirty working tree, always use a new branch, never modify the current branch.
- [ ] **Test verification harness:** `./mvnw test` before and after, compared.
- [ ] **Vulnerability-driven dependency bumps:** to the fixed versions reported by OSV.
- [ ] **Change report and `--open-pr`:** recipes applied, diff summary, test changes; open a PR via the GitHub API.
- [ ] **Opt-in agent repair loop:** failing tests + diff → Claude, with an iteration limit.
- [ ] **MCP tools:** `plan_upgrade`, `run_upgrade`.
- [ ] **Public upgrade benchmark:** success rate across 20 real open-source Spring repos.

## Phase 3 — Conventions + Hooks
- [ ] **`stealth learn`:** layering, preferred libraries, naming conventions and a helper index → `.stealth/conventions.yml` + `CONVENTIONS.md`.
- [ ] **Generate ArchUnit layer rules** from observed dependencies between layers.
- [ ] **Duplicate-intent detection:** CPD + helper name/signature similarity ("use `DateUtils.parseIso` instead").
- [ ] **`stealth check --changed`:** analyze only the git diff, fast.
- [ ] **`stealth hooks install`:** Claude Code Stop hook in `.claude/settings.json`; output goes back to the agent.
- [ ] **MCP tools:** `get_conventions`, `find_existing_helper(description)`.
- [ ] **Export conventions** to AGENTS.md, `.cursorrules` and Copilot instructions.

## Phase 4 — Debt Budget
- [ ] **Debt change calculation** between a PR's base and head commits.
- [ ] **AI attribution:** `Co-Authored-By` trailers, bot authors, agent branch naming.
- [ ] **Debt budget GitHub Action:** PR comment with debt change, AI vs human split, pass/fail.
- [ ] **Budget policy in `.stealth.yml`.**
- [ ] **Metrics export** in the format the platform ingests.

## Phase 5 — Platform
- [ ] **Spring Boot API and scan ingestion:** orgs, repos, `stealth upload`, Postgres, GitHub OAuth.
- [ ] **GitHub App:** org-wide repo discovery and scheduled scans.
- [ ] **React dashboard:** fleet health, repo drill-down, combined tech + security backlog.
- [ ] **Campaigns:** one fix → upgrade PRs across many repos, with progress tracking.
- [ ] **Debt in money terms:** effort-hours × rate per finding type.
- [ ] **Teams, RBAC and SSO** (SSO in the enterprise tier).
- [ ] **Stripe billing and tiers:** Free CLI / Team per developer / Enterprise.
- [ ] **Self-hosted distribution:** Docker Compose, then Helm.

## Phase 6 — Prune
- [ ] **Runtime usage Java agent:** samples which classes and endpoints are used.
- [ ] **Unused dependency detection:** jdeps + runtime evidence.
- [ ] **Dead endpoint detection:** Spring request mappings × access logs.
- [ ] **Removal PRs** verified by the test harness.
- [ ] **Attack-surface reduction report** for security buyers.

## Business & go-to-market (runs in parallel)
- [ ] **Positioning and landing page:** "Your repos maintain themselves", with a waitlist before the Phase 1 launch.
- [ ] **Competitive analysis:** Moderne, SonarQube, Renovate/Mend, CodeScene, AWS Transform, Copilot app modernization.
- [ ] **Recruit 5 design partners** with large Spring Boot 2 estates.
- [ ] **Pricing experiments:** per developer vs per repo.
- [ ] **Usage stats and metrics:** opt-in CLI stats; stars, weekly active repos, PRs merged, upgrade success rate.
- [ ] **Content plan:** public benchmark, "state of Java tech debt" report, conference talk.
- [ ] **Fundraising deck** once there's traction.
- [ ] **Track risks** (below).

## Risks
- **OpenRewrite recipe licensing:** audit before Phase 2. Use only Apache-2.0 recipes in the paid platform, or write your own.
- **Competing with Moderne** on migrations. Differentiate with the combined tech + security score, agent-native prevention (Phase 3) and the AI debt budget (Phase 4).
- **Rate limits** on Maven Central and OSV: cache aggressively, batch queries.
- **False positives** erode trust: every analyzer ships with precision tests on fixtures.
- **LLM cost and quality** in the repair loop: opt-in, limited iterations, never required for the core value.
