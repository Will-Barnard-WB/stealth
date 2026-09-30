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

- [x] WireMock tests for `MavenCentralClient`: metadata parsing, cache hit/miss/TTL, offline mode, 429/5xx retry (`MavenCentralClientTest`, `CachedHttpClientTest`)
- [x] Precision tests: `fixtures/boot2-legacy` flags the pinned outdated dependencies, and `fixtures/boot4-clean` produces **no** freshness findings (with a pinned "latest" snapshot served by WireMock)
- [x] Pre-release filtering unit tests (`VersionsTest`)
- [x] Managed dependencies collapsed into one finding on the parent/BOM
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **Rules:** three rules instead of one, `deps/outdated-major` (medium), `deps/outdated-minor` (low) and `deps/outdated-patch` (info), because ADR-0001 gives each rule one default severity. It also lets `.stealth.yml` silence patch-level noise on its own. Documented in `docs/rules/deps.md`. Confirm in review: rule ids are permanent once released.
- **Version source:** `maven-metadata.xml` from `repo.maven.apache.org`, as proposed. `HttpCache` + `CachedHttpClient` (`core/http`) are ready for the OSV and endoflife.date clients.
- **Precision traps found in the recorded data:** `commons-collections`' newest "version" is `20040616` (date-stamped, older than 3.2.2); Spring Boot's newest is `4.2.0-M2`; guava publishes `-jre` and `-android` side by side. All three are handled and tested.
- **Fixture README correction:** commons-lang3 3.21.0 was released on 2026-09-25, so at the reference date `multi-module`'s managed 3.20.0 (`pom.xml:48`) is outdated too.
- **Partial lookups fail the analyzer** (no "incomplete" status in the SPI yet), so one unreachable artifact hides the others' results. Revisit if the runner gets a partial status.
- **`--offline`** added to `stealth doctor`; it also switches the Maven model loader to the local repository only.

### Out of scope

- Opening upgrade PRs: Phase 2
- Plugin (`<build><plugins>`) freshness: follow-up once the dependency path is proven

Suggested branch: `feature/CU-<clickup-id>_dependency_freshness_analyzer`
Depends on: Maven model loading
