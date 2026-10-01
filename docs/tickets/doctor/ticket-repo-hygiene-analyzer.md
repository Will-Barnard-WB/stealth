# Repo hygiene analyzer

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Some debt isn't in the code but in how the repo is run: no CI, no tests, no ownership, dozens of dead branches, large binaries committed. These are cheap to detect and are strong predictors of a repo nobody is looking after, but `stealth doctor` doesn't report them yet.

### Who is impacted?

CLI users and, later, platform users comparing hygiene across a fleet of repos (Phase 5).

### Why it's important or urgent?

It rounds out the tech score with signals every repo has, including non-Maven ones. Missing CODEOWNERS and CI also matter to security reviewers.

### What is the proposed solution?

Tech findings for: stale branches, large committed files, and missing CODEOWNERS, CI configuration or tests.

### Technical approach

- **`core`**: `RepoHygieneAnalyzer` (category tech), using JGit through the shared `Repository` in `RepoContext`
- **Stale branches**: local and remote-tracking branches whose last commit is older than `hygiene.staleBranchAfter` (default `P90D`), excluding the default branch. One finding summarizing the count, listing the names
- **Large files**: files in the HEAD tree over `hygiene.largeFileBytes` (default 5MB), excluding paths ignored in `.stealth.yml`
- **Missing CODEOWNERS**: none of `CODEOWNERS`, `.github/CODEOWNERS`, `docs/CODEOWNERS`
- **Missing CI**: none of `.github/workflows/*.yml`, `.gitlab-ci.yml`, `Jenkinsfile`, `azure-pipelines.yml`, `.circleci/config.yml`, `bitbucket-pipelines.yml`
- **Missing tests**: a module with `src/main/java` but no `src/test/java` (per module from `MavenProjectModel`)
- Not a git repo: skip the git-based checks with an `AnalyzerResult` note, still run the file checks
- Tests build git history programmatically with JGit in `@TempDir` (commits with fixed dates), not in committed fixtures

### Definition of done

- [x] Unit tests for each check, with JGit repos built in `@TempDir`
- [x] Precision tests: `fixtures/boot4-clean` produces **no** hygiene findings, and `fixtures/boot2-legacy` flags missing CODEOWNERS/CI
- [x] A non-git directory doesn't fail the analyzer
- [x] Thresholds configurable and tested
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **Rules** (all low): `hygiene/missing-codeowners`, `hygiene/missing-ci`, `hygiene/missing-tests` (per module, at its `pom.xml`), `hygiene/stale-branches` (one finding listing the names), `hygiene/large-file` (per file). Documented in `docs/rules/hygiene.md`.
- **Branch checks only run at a work-tree root.** For a subdirectory of a repository (a service in a monorepo, or a fixture inside stealth's own repo) the branches belong to the whole repository, so reporting them would be noise; the file checks still run. A non-git directory skips them the same way. There's no SPI status for "partly skipped", so it's silent.
- **Excluded branches:** the checked-out branch, `origin/HEAD`'s target, `main` and `master`. A local branch and its `origin/` copy count once, by their newest commit.
- **Large files** come from `WorkingTreeFiles` (tracked, plus untracked files `.gitignore` doesn't exclude), not just the HEAD tree, so a big file about to be committed is caught too.
- **Thresholds:** `stealth.hygiene.stale-branch-after` (default `P90D`) and `stealth.hygiene.large-file-bytes` (default 5 MB) until `.stealth.yml` adds `hygiene.*`.
- `stealth doctor --hygiene` runs it alone.

### Out of scope

- Large files in git history (not just HEAD): follow-up, can share the history walk with secrets-in-history
- Branch protection and other GitHub settings: needs the GitHub API, platform phase

Suggested branch: `feature/CU-<clickup-id>_repo_hygiene_analyzer`
Depends on: analyzer SPI, Maven model loading (for per-module test check)
