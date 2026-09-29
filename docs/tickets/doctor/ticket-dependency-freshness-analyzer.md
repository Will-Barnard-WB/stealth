# Dependency freshness analyzer

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Teams don't know how far behind their dependencies are. Outdated dependencies are the most common source of tech debt and make later upgrades harder. `stealth doctor` should list every dependency that has a newer stable release, and how far behind it is.

### Who is impacted?

CLI users, and AI agents calling `check_dependency` / `list_findings` over MCP.

### Why it's important or urgent?

This is the headline "tech debt" signal of the doctor report and feeds the scoring engine. The `MavenCentralClient` and HTTP cache introduced here are reused by the maintenance analyzer and the MCP `check_dependency` tool.

### What is the proposed solution?

A finding for each dependency (and parent/BOM) where a newer stable version exists, with severity based on how far behind it is (major > minor > patch) and the latest version as the suggested fix.

### Technical approach

- **`core`**:
  - `MavenCentralClient`: fetches the available versions for a `groupId:artifactId`. The roadmap says the Central search API. **Proposed**: use `repo.maven.apache.org/maven2/<g>/<a>/maven-metadata.xml` for the version list (more reliable, no search rate limits) and the search API only for release timestamps (maintenance analyzer). Confirm in review
  - `HttpCache` in `~/.stealth/cache` (JSON files keyed by URL, 24h TTL, `--offline` serves stale entries), shared with the OSV and endoflife.date clients
  - `DependencyFreshnessAnalyzer` (category tech): compares each effective version from `MavenProjectModel` with the latest **stable** version
- Version comparison with `ComparableVersion` from maven-artifact. Filter pre-releases (`-alpha`, `-beta`, `-M1`, `-RC`, `-SNAPSHOT`, `.CR`, `-preview`) unless the current version is itself a pre-release
- Severity: major behind → medium, minor → low, patch → info (final values per ADR-002)
- Findings for BOM/parent-managed dependencies point at the parent/BOM, e.g. "upgrade `spring-boot-starter-parent`", instead of listing 40 individual starters
- Concurrency: bounded parallel fetches (e.g. 8) so we don't hammer Central
- **Risk**: rate limits and flaky Central. Cache aggressively, retry with backoff, and on failure mark the analyzer incomplete rather than reporting "up to date"

### Definition of done

- [ ] WireMock tests for `MavenCentralClient`: metadata parsing, cache hit/miss/TTL, offline mode, 429/5xx retry
- [ ] Precision tests: `fixtures/boot2-legacy` flags the pinned outdated dependencies, and `fixtures/boot3-clean` produces **no** freshness findings (with a pinned "latest" snapshot served by WireMock)
- [ ] Pre-release filtering unit tests
- [ ] Managed dependencies collapsed into one finding on the parent/BOM
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Opening upgrade PRs: Phase 2
- Plugin (`<build><plugins>`) freshness: follow-up once the dependency path is proven

Suggested branch: `feature/CU-<clickup-id>_dependency_freshness_analyzer`
Depends on: Maven model loading
