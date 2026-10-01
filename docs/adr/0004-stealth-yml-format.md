# ADR-0004: `.stealth.yml` format

- **Status:** Accepted
- **Date:** 2026-09-29
- **Ticket:** CU-869f96kzk

## Context

Users need to tune `stealth doctor` per repo: skip generated code and fixtures, downgrade rules that don't matter to
them, accept specific secrets or dependencies, and set the CI gate. The file is committed to repos and read by every
future stealth version, so its format is a contract. Later phases will add sections (Phase 3 conventions, Phase 4 debt
budget), so v1 must be easy to extend without breaking old files.

## Decision

### Location

A single `.stealth.yml` at the repository root. No inheritance, includes or per-module files in v1. All paths in it are
relative to the repo root. If `.stealth.yaml` also exists, stealth fails with an error rather than guessing.

### Example

```yaml
version: 1

# Paths stealth never analyses. gitignore-style globs, relative to the repo root.
# .git/ and **/target/** are always ignored.
ignore:
  - "fixtures/**"
  - "**/generated/**"
  - "legacy-module/**"          # excludes the Maven module too, including its dependencies

# Per-rule severity overrides: critical | high | medium | low | info | off
severity:
  deps/outdated: low
  duplication/cpd: info
  hygiene/missing-codeowners: off

# Accepted findings. Each entry needs a reason; `expires` is optional.
allow:
  secrets:
    - path: "src/test/resources/fake-credentials.properties"
      reason: Test fixtures, not real keys
    - fingerprint: "v1:c77e0939cbd45a129c6cbb29a6edaa001f40e8b2954e3714c1c2532257ee7bd7"
      reason: Key revoked on 2026-08-14, removal from history tracked in #123
  dependencies:
    - component: "pkg:maven/commons-collections/commons-collections"   # no version = any version
      rules: [deps/unmaintained]
      reason: Only used by the legacy importer, which is being removed
      expires: 2026-12-31
    - advisory: GHSA-jjjh-jjxp-wpff
      reason: Not reachable, we never deserialise untrusted JSON with this mapper
      expires: 2026-11-30

# CI gate. A single number applies to the overall score; a map sets per-category minimums.
fail-under:
  overall: 70
  security: 80
```

### Keys

| Key | Type | Meaning |
|---|---|---|
| `version` | int | Schema version. Required; `1` for this ADR. |
| `ignore` | list of globs | Files and directories excluded from all analyzers. A glob matching a module directory excludes that module. |
| `severity` | map of `ruleId` → level | Overrides a rule's severity. `off` disables the rule. Advisory rules are overridden by base id (`vuln/known-vulnerability`), not per advisory; use `allow` for single advisories. |
| `allow.secrets` | list | Each entry matches by `path` (glob) or `fingerprint`, plus optional `rules` to narrow it to specific secret rules. |
| `allow.dependencies` | list | Each entry matches by `component` (Package URL, version optional) and/or `advisory` (id or alias), plus optional `rules`. |
| `allow.*[].reason` | string | Why this is accepted. Shown in `--verbose` output. |
| `allow.*[].expires` | date | After this date (UTC) the entry stops applying and stealth warns. |
| `fail-under` | int, or map with `overall` / `tech` / `security` | Minimum scores. `--fail-under N` on the command line overrides `overall`. |
| `analyzers.<id>.enabled` | bool | `false` turns the analyzer off. *(Added with the config ticket.)* |
| `analyzers.<id>.<setting>` | depends | Analyzer thresholds: `maintenance.stale-after`, `duplication.min-tokens` / `include-tests`, `hygiene.stale-branch-after` / `large-file-bytes`. *(Added with the config ticket.)* |
| `eol.java-distribution` / `eol.java-version` | string | Which JDK's end-of-life dates apply, and the runtime Java when it isn't the compile target. *(Added with the EOL analyzer.)* |

Keys use kebab-case to match CLI flags; camelCase spellings of the same keys are accepted too. A JSON Schema for editors
is published at `docs/schema/stealth-yml.schema.json`. Rule ids and severities are the ones from [ADR-0001](0001-finding-model.md).

### Validation

- **Unknown keys warn, don't fail.** stealth prints `warning: .stealth.yml:14: unknown key 'ignroe'`, with a "did you
  mean" suggestion, and carries on. This lets an older stealth read a file written for a newer one, and lets later
  phases add sections.
- **Known keys with invalid values fail** with exit code 2 (config error): a wrong type, an unknown severity, a
  malformed date, `fail-under` outside 0–100. Silently ignoring a broken `fail-under` would make CI gates pass by
  accident, which is worse than a failed build.
- An unknown **rule id** in `severity` or `allow.*.rules` warns (it may be a rule from a newer version).
- An allowlist entry without `reason` warns. An entry that matched nothing in this run is reported with `--verbose`
  so stale entries can be cleaned up.
- Missing `version` → treated as `1`, with a warning. `version` higher than this stealth supports → error, "this file
  needs stealth ≥ x.y".

### Order of application

1. `ignore` paths are excluded before analyzers run.
2. Analyzers produce findings with default severities.
3. `severity` overrides are applied; `off` findings are dropped.
4. `allow` entries drop matching findings (and warn about expired entries).
5. What remains is scored ([ADR-0002](0002-scoring-formula.md)) and rendered ([ADR-0003](0003-sarif-mapping.md)).

### Evolving the format

New optional sections or keys are added within `version: 1`. Renaming or removing a key, or changing what an existing
key means, needs `version: 2`, and stealth keeps reading `version: 1` files for at least one major release after that.

## Consequences

- Users can change their own score through overrides and allowlists. That's intended: the file is committed, so it's
  reviewed like code, and `reason`/`expires` keep the decisions visible.
- Warnings for unknown keys mean typos don't break builds, but they can go unnoticed. The terminal output should make
  config warnings prominent.
- A JSON Schema for the file (for editor autocompletion) is a natural follow-up to the `.stealth.yml` ticket.

## Alternatives considered

- **Fail on unknown keys:** catches typos, but every new key would break older stealth versions reading newer files,
  which is common in CI where versions are pinned differently across repos.
- **Suppression comments in code** (`// stealth:ignore`): useful for duplication and secrets, but doesn't work for
  dependency findings and spreads decisions across the codebase. Can be added later alongside the file.
- **A separate baseline file** of accepted fingerprints: good for adopting on a large legacy repo; likely later
  (`stealth baseline`), and it would build on the same fingerprints.
- **TOML or JSON:** YAML is what Java/Spring users already use for `application.yml` and GitHub Actions.

## Expected to change

- Whether `allow` needs a general `findings` list (by fingerprint, for any rule) rather than only secrets and dependencies.
- Default ignored paths (e.g. `**/node_modules/**` once npm support arrives).
- `fail-under` may grow per-analyzer thresholds, and Phase 4 will add a `budget` section.
