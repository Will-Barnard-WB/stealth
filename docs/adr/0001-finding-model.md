# ADR-0001: Finding model

- **Status:** Proposed
- **Date:** 2026-09-29
- **Ticket:** CU-869f96kzk

## Context

Every analyzer produces findings, and everything downstream consumes them: the scoring engine ([ADR-0002](0002-scoring-formula.md)),
the terminal/JSON/SARIF renderers ([ADR-0003](0003-sarif-mapping.md)), `.stealth.yml` overrides and allowlists
([ADR-0004](0004-stealth-yml-format.md)), the MCP server, and later the debt budget (Phase 4) and platform (Phase 5).

The scaffold's `Finding(ruleId, message)` is a placeholder. Before the first analyzer lands we need to fix which fields
exist, which are stable across runs and versions, and how the same finding is recognised from one run to the next.

## Decision

### The record

```java
public record Finding(
        String ruleId,                     // stable rule identifier, see below
        Category category,                 // TECH or SECURITY
        Severity severity,                 // after .stealth.yml overrides
        String message,                    // one line, human-readable, may change between versions
        Location location,
        Optional<String> component,        // Package URL of the dependency involved, if any
        Optional<Advisory> advisory,       // for vulnerability findings
        Optional<Remediation> remediation,
        String fingerprint) {}             // stable identity, see below

public enum Category { TECH, SECURITY }

public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

public record Location(
        Optional<String> path,             // repo-relative, forward slashes; empty for repo-level findings
        OptionalInt line,                  // 1-based
        Optional<String> module) {}        // repo-relative module directory ("" = root module)

public record Advisory(String id, List<String> aliases, OptionalDouble cvssScore, Optional<URI> url) {}

public record Remediation(Optional<String> fixedVersion, Optional<String> description) {}
```

Each analyzer also publishes the rules it can report, which the SARIF `rules[]` array and `--list-rules` are built from:

```java
public record Rule(String id, String name, String shortDescription, URI helpUri,
                   Category category, Severity defaultSeverity) {}
```

### `ruleId`

- Format `<analyzer>/<rule>`, lowercase kebab-case, e.g. `deps/outdated`, `vuln/known-vulnerability`,
  `secrets/aws-access-key`, `hygiene/missing-codeowners`.
- **Stable forever once released.** A rule is never renamed; a retired id is never reused. Users reference ids in
  `.stealth.yml`, and GitHub code scanning keys alert history on them.
- Advisory ids (CVE/GHSA) are *not* part of `ruleId`. One rule, many advisories.

### Category and severity

- A finding has exactly one category. If a problem is both (an end-of-life runtime is old *and* unpatched), the category
  is where the main risk lies: **security** if it can be exploited, otherwise **tech**.
- Severity comes from the rule's default, except for vulnerabilities, where it comes from the CVSS base score using the
  usual bands: ≥ 9.0 critical, 7.0–8.9 high, 4.0–6.9 medium, 0.1–3.9 low. No score → the advisory's own rating if it
  has one, else medium.
- `INFO` findings are shown but never affect the score.
- `.stealth.yml` severity overrides are applied before the finding reaches scoring and renderers; the finding carries
  the effective severity.

### Location

- Paths are relative to the repository root, use `/`, and never contain absolute paths (output gets shared and uploaded).
- File-level findings (secrets, duplication) set `path` and `line`.
- Dependency findings point at the `pom.xml` that declares the dependency, with the line of its `<dependency>` element
  if it can be found. A transitive dependency points at the direct dependency that brings it in. `component` holds the
  Package URL, e.g. `pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.13.1`.
- Repo-level findings (missing CODEOWNERS, stale branch) leave `path` empty. How renderers anchor them is in ADR-0003.

### Fingerprint

The fingerprint identifies "the same problem" across runs, so that baselines, allowlists, SARIF alert tracking and the
Phase 4 debt diff all agree.

```
fingerprint = "v1:" + hex(sha256(ruleId + "\0" + key₁ + "\0" + key₂ + ...))
```

Each rule chooses its identity keys. Keys must survive unrelated edits, so **never** include line numbers, messages or
anything else that moves when code around the finding changes.

| Kind | Identity keys |
|---|---|
| Dependency (outdated, unmaintained) | module, `groupId:artifactId` (no version, so a partial upgrade is the same finding) |
| Vulnerability | module, `groupId:artifactId`, advisory id (the primary id, not the alias) |
| Secret | path, sha256 of the secret value (the value itself is never stored or printed) |
| Duplication | sorted paths of the duplicated blocks, hash of the normalised token sequence |
| End-of-life | module, product (`java`, `spring-boot`) |
| Repo hygiene | subject: branch name, file path, or the missing file's expected name |

A shared helper (`Fingerprints.of(ruleId, String... keys)`) computes it, so analyzers can't hash differently.
The `v1:` prefix lets us change the algorithm later without silently mismatching old baselines.

### JSON output

`--json` serialises findings using these field names, inside an envelope carrying `"schemaVersion": 1`. Adding
optional fields is not a breaking change; removing or renaming one bumps `schemaVersion`.

## Consequences

- The `Finding` record in `core` changes shape in the Analyzer SPI ticket; the current two-field record is a placeholder.
- Rule ids and fingerprints become user-facing contracts: they appear in `.stealth.yml`, SARIF and JSON. Renaming a
  rule effectively resets its history for users.
- Analyzers must think about identity up front, which is also what makes precision tests on fixtures meaningful.
- Findings carry no timestamps or run ids; that's the platform's job (Phase 5).

## Alternatives considered

- **Free-form `Map<String, Object>` properties instead of typed optional fields:** flexible, but every renderer would
  need to know analyzer-specific keys. The few cross-cutting fields (component, advisory, fixed version) are typed; we
  can add a properties bag later if a real need appears.
- **Advisory id in the rule id** (`vuln/CVE-2022-42003`): what some scanners do, but it makes `ruleId` unbounded and
  breaks per-rule severity overrides. ADR-0003 handles GitHub's per-rule severity display separately.
- **Fingerprint from path + line + message:** simple but churns on every unrelated edit, which floods baselines and
  closes and reopens GitHub alerts.
- **Numeric severity (0–10) everywhere:** too precise for non-vulnerability rules; five levels match SARIF and GitHub.

## Expected to change

- The identity keys per rule kind, as analyzers get written. Any change that alters fingerprints for existing findings
  must be called out in release notes.
- Whether `Location` needs an end line or column (duplication may want ranges).
- A `confidence` field if some analyzers (secrets entropy rules) turn out noisy enough to need it.
