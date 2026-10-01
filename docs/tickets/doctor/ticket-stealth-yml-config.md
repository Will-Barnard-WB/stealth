# `.stealth.yml` config

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Every repo has findings the team has consciously accepted: a test fixture that looks like a secret, a vendored directory, a dependency pinned on purpose. Without a way to record that in the repo, users can't get a clean, trustworthy report or a stable CI gate, and they'll turn the tool off.

### Who is impacted?

CLI and CI users, plus the MCP server (which should respect the same config).

### Why it's important or urgent?

It's the main tool for managing false positives, which are a top ROADMAP risk. `--fail-under` in CI isn't usable without it.

### What is the proposed solution?

An optional `.stealth.yml` at the repo root (or passed with `--config`) supporting ignored paths, severity overrides per rule, allowlists (secrets, dependencies), analyzer enable/disable and thresholds, and `fail-under`, as specified in ADR-004.

### Technical approach

- **`core`**: `StealthConfig` record tree + `StealthConfigLoader` (Jackson YAML), exposed on `RepoContext`
- Example:
  ```yaml
  version: 1
  ignore:
    - "legacy/**"
    - "**/generated/**"
  severity:
    dependency-outdated-patch: off
    eol-java: medium
  allow:
    secrets:
      - fingerprint: "sha256:ab12..."
        reason: "test fixture"
    dependencies:
      - "com.example:pinned-lib"
  analyzers:
    duplication: { enabled: true, minTokens: 150 }
    maintenance: { staleAfter: P3Y }
  fail-under: 70
  ```
- Precedence: CLI flags > `.stealth.yml` > defaults
- `ignore` globs apply to every file-based analyzer via `RepoContext`, not re-implemented per analyzer
- Validation: unknown keys print a warning with file and line number, and invalid values fail with exit code 2 and a clear message. `version` is required once the schema has a v2
- Publish a JSON Schema for editor autocomplete (`docs/schema/stealth-yml.schema.json`)

### Definition of done

- [x] Loader tests: full example, empty file, missing file (defaults), unknown keys warn, invalid values fail with line numbers
- [x] Integration tests: `ignore` removes findings in `fixtures/with-secrets`, a severity override changes the score, `fail-under` drives the exit code
- [x] JSON Schema published and the example validates against it
- [x] README section documenting every key
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **Format follows ADR-0004**, not the older example above (rule ids like `deps/outdated-major`, `fail-under` as a number or `overall`/`security`/`tech`). Added within version 1: `analyzers.<id>.enabled` and the analyzer thresholds (`maintenance.stale-after`, `duplication.min-tokens`/`include-tests`, `hygiene.stale-branch-after`/`large-file-bytes`); ADR-0004's keys table updated.
- **Loader:** `StealthConfigLoader` uses snakeyaml-engine's node tree, so every warning and error carries a line number. camelCase keys are accepted (the EOL docs used `javaDistribution`).
- **Where it applies:** `ignore` in `WorkingTreeFiles` (secrets, duplication, large files) and in Maven module discovery (an ignored module directory takes its dependencies with it), before analyzers run; severity overrides and allowlists in `AnalyzerRunner`, after them, so every analyzer gets them. Thresholds are read per run from `RepoContext.config()`; the `stealth.*` properties remain the defaults.
- **Precedence:** `--fail-under` replaces `fail-under.overall`; category thresholds stay.
- **Not done yet:** `--verbose` listing of allowlist entries that matched nothing (ADR-0004); no `--verbose` flag exists yet.

### Out of scope

- Debt budget policy: Phase 4
- Conventions config (`.stealth/conventions.yml`): Phase 3
- Org-wide config inheritance: platform phase

Suggested branch: `feature/CU-<clickup-id>_stealth_yml_config`
Depends on: ADR-004, analyzer SPI
