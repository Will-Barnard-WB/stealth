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

- [ ] Unit tests for each check, with JGit repos built in `@TempDir`
- [ ] Precision tests: `fixtures/boot3-clean` produces **no** hygiene findings, and `fixtures/boot2-legacy` flags missing CODEOWNERS/CI
- [ ] A non-git directory doesn't fail the analyzer
- [ ] Thresholds configurable and tested
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Large files in git history (not just HEAD): follow-up, can share the history walk with secrets-in-history
- Branch protection and other GitHub settings: needs the GitHub API, platform phase

Suggested branch: `feature/CU-<clickup-id>_repo_hygiene_analyzer`
Depends on: analyzer SPI, Maven model loading (for per-module test check)
