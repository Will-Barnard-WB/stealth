# Fixture repos for analyzer precision tests

ClickUp: [CU-869f96kz4](https://app.clickup.com/t/869f96kz4)

### What is the problem we are trying to solve?

Every analyzer in Phase 1 needs known inputs to test against: repos where a finding **must** appear and repos where it **must not**. Without shared fixtures, each analyzer invents its own test data, coverage drifts, and false positives go unnoticed.

### Why it's important or urgent?

False positives erode trust, and the ROADMAP lists them as a key risk. Fixtures are the basis of every analyzer's precision tests and of the Phase 2 upgrade tests, so they need to exist before the first analyzer ticket starts.

### What is the proposed solution?

A `fixtures/` directory with five small, deterministic Maven projects, each representing one kind of repo `stealth doctor` must handle, plus a README describing what each is expected to trigger.

### Technical approach

| Fixture | Contents | Expected findings |
|---|---|---|
| `boot2-legacy` | Spring Boot 2.7, Java 11, `javax.*`, a few outdated and one known-vulnerable dependency pinned | outdated deps, CVE with fixed version, Boot + Java EOL |
| `boot3-clean` | Current Spring Boot 3.x, Java 21, up-to-date deps, CODEOWNERS, CI config, tests | **none**: the false-positive baseline |
| `with-secrets` | Fake AWS key, GitHub token, private key block, high-entropy string, plus look-alikes that must not match (UUIDs, hashes in lockfiles) | secrets only for the real patterns |
| `duplicated` | Copy-pasted helper classes above and below the CPD threshold | duplication only above threshold |
| `multi-module` | Parent POM + BOM import + 3 modules, versions managed through the parent and properties | correct effective versions per module; no duplicate findings per module |

- Each fixture is a real buildable Maven project (`mvn -q validate` passes offline where possible) but minimal. No real business code
- Secrets are obviously fake (`AKIAIOSFODNN7EXAMPLE`-style) and allowlisted for GitHub push protection / secret scanning so they don't trip alerts on our own repo
- `fixtures/README.md` lists each fixture's expected findings. Analyzer tests assert against this
- Fixtures are read-only in tests. Anything that mutates copies into `@TempDir` first
- Pinned vulnerable versions must not be built into our artifacts. Fixtures are excluded from the reactor (not listed as modules) and from Dependabot
- **Risk**: fixtures go stale as "latest version" moves. Freshness/EOL tests use an injected `Clock` and stubbed registry responses, never live data

### Definition of done

- [ ] Five fixtures committed under `fixtures/` with a README of expected findings
- [ ] Fixtures excluded from the Maven reactor, Dependabot and our own secret scanning
- [ ] Each fixture's expected findings reviewed by a second person
- [ ] A test helper to copy a fixture into `@TempDir` exists in `core` test sources

### Out of scope

- Gradle and npm fixtures: added when those ecosystems are supported
- Git-history fixtures (secrets in history, stale branches): built programmatically with JGit inside the hygiene/secrets analyzer tests

Suggested branch: `feature/CU-869f96kz4_fixture_repos`
Can run in parallel with the scaffold.
