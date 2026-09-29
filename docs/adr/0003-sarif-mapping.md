# ADR-0003: SARIF mapping

- **Status:** Proposed
- **Date:** 2026-09-29
- **Ticket:** CU-869f96kzk

## Context

`stealth doctor --format sarif` writes SARIF 2.1.0 so findings show up in GitHub code scanning (and other SARIF
viewers). GitHub keys alert history on the rule id and the result fingerprints: if either changes between runs, alerts
are closed and reopened as new ones, and users lose their triage state. So the mapping from [ADR-0001](0001-finding-model.md)
findings to SARIF has to be stable, and it has to work within GitHub's quirks:

- GitHub reads `security-severity` from the **rule**, not the result, so every result under one rule shows the same
  security severity.
- GitHub shows results against a file and line, but some findings aren't about a file (a
  vulnerable transitive dependency, a missing CODEOWNERS file, a stale branch).

## Decision

A full, schema-valid sample is in [examples/doctor.sarif](examples/doctor.sarif).

### Run

- One `run` per invocation. `tool.driver.name` = `stealth`, plus `semanticVersion` and `informationUri`.
- `automationDetails.id` = `stealth/doctor/`, so GitHub treats stealth uploads as their own category.
- `run.properties.stealthScore` carries `overall`, `tech`, `security` and `scoringVersion` ([ADR-0002](0002-scoring-formula.md)).
  GitHub ignores it; other consumers can use it.
- No `originalUriBaseIds`: defining `%SRCROOT%` would need an absolute path, which leaks the local machine's layout.

### Rules

- `rules[]` contains one entry per rule that has at least one result in this run, referenced from results by `ruleId`
  and `ruleIndex`.
- SARIF rule `id` = the finding's `ruleId`, **except for findings with an advisory**, where it is
  `<ruleId>/<advisoryId>` (e.g. `vuln/known-vulnerability/GHSA-jjjh-jjxp-wpff`). That gives each advisory its own
  `security-severity`, title and help link in GitHub. The base id is kept in `properties.stealthRuleId`.
- `name` is the PascalCase form of the rule's last segment. `shortDescription`, `fullDescription`, `helpUri` come from
  the `Rule` descriptor; advisory rules use the advisory summary and its OSV.dev page.
- `properties.tags`: `security` or `maintainability` from the category, plus `dependency` / `secret` where relevant,
  plus `external/cwe/cwe-NNN` when the advisory lists a CWE.
- `properties.precision`: `high` by default; rules we know to be noisier (entropy-based secrets) use `medium`.

### Severity

| Severity | `level` | `security-severity` (security rules only) |
|---|---|---|
| Critical | `error` | 9.5 |
| High | `error` | 8.0 |
| Medium | `warning` | 5.5 |
| Low | `note` | 2.0 |
| Info | `note` | not set |

For advisory rules, `security-severity` is the advisory's CVSS base score when there is one, otherwise the value from
the table. Tech rules never set `security-severity`, so GitHub shows them as ordinary code-quality alerts.
`defaultConfiguration.level` is the rule's default severity; `result.level` is always set to the finding's effective
severity (after `.stealth.yml` overrides).

### Locations

Every result has exactly one physical location, relative to the repo root with `uriBaseId: "%SRCROOT%"`, plus a
`logicalLocation` for the Maven module (`kind: "module"`, `name` = module directory, `.` for the root).

| Finding | `physicalLocation` |
|---|---|
| File finding (secret, duplication) | the file and `startLine` |
| Direct dependency | the declaring `pom.xml`, line of the `<dependency>` (or `<dependencyManagement>` / version property) |
| Transitive dependency | the module's `pom.xml`, line of the direct dependency that pulls it in |
| Line unknown | the file, `startLine: 1` |
| Repo-level (hygiene, EOL runtime without a file) | the root build file (`pom.xml`) at line 1, with `logicalLocation` `kind: "repository"` |

The message always names the real subject ("… applies to the whole repository", the branch name, the dependency
coordinates) since the anchor line alone would be misleading.

### Fingerprints

- `partialFingerprints` = `{ "stealth/v1": <finding.fingerprint> }`. It's line-independent (ADR-0001), so GitHub keeps
  tracking an alert when code moves. We don't emit `primaryLocationLineHash`; GitHub computes that itself.
- If the fingerprint algorithm ever changes, the key changes with it (`stealth/v2`), rather than silently changing
  values under the old key.

### Result properties

`properties` carries the fields SARIF has no slot for: `severity` (our five-level value), and when present
`component` (Package URL), `advisory`, `aliases`, `fixedVersion`. We don't emit SARIF `fixes`: those describe file
edits, and we don't produce edits until Phase 2.

### Suppressed findings

Findings removed by `.stealth.yml` (ignored paths, rules set to `off`, allowlist entries) are **not** emitted. A
dismissed alert in GitHub then closes as fixed, which is the behaviour users expect from an allowlist.

## Consequences

- The SARIF renderer is a pure mapping from findings + rule descriptors; the sample file should become a golden-file
  test in the renderers ticket, validated against the SARIF 2.1.0 schema in CI.
- Anchoring repo-level findings to `pom.xml:1` means GitHub shows them against the build file. Acceptable, but users
  may find it odd; the message wording matters.
- Per-advisory rule ids make `rules[]` grow with the number of advisories found. That's normal for dependency scanners
  and GitHub handles it.

## Alternatives considered

- **One SARIF rule per `ruleId` only:** simplest, but every vulnerability would show the same `security-severity` in
  GitHub, so a critical RCE looks like a low-severity bug.
- **Results without a physical location for repo-level findings:** valid SARIF, but GitHub can't show them against any file, so they're easy to miss or dropped.
- **Emitting suppressed findings with `suppressions[]`:** keeps an audit trail in the file, but tool support differs.
  We can add it later if users ask for it; it doesn't change any existing ids or fingerprints.
- **Line-based fingerprints (let GitHub compute them):** churns on edits and doesn't work for dependency findings.

## Expected to change

- How repo-level findings are anchored, once we see how they look in GitHub's UI.
- Tags, and whether to include `help.markdown` for every rule.
- Whether to emit `suppressions[]` for allowlisted findings.

## Validating

The sample was validated with the OASIS schema
(`sarif-2.1/schema/sarif-schema-2.1.0.json` in `oasis-tcs/sarif-spec`), e.g.:

```sh
curl -sSLO https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/sarif-2.1/schema/sarif-schema-2.1.0.json
python3 -c 'import json,jsonschema; jsonschema.Draft4Validator(json.load(open("sarif-schema-2.1.0.json"))).validate(json.load(open("docs/adr/examples/doctor.sarif")))'
```
