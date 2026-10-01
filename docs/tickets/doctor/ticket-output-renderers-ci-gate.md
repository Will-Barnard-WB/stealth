# Output renderers and CI gate (terminal, JSON, SARIF, --fail-under)

ClickUp: _not yet created_

### What is the problem we are trying to solve?

The doctor report needs to reach three audiences: a human in a terminal, scripts and agents that want structured data, and GitHub code scanning (SARIF). CI users also need the run to fail when health drops below a threshold. Right now `stealth doctor` only prints a placeholder summary.

### Who is impacted?

CLI users, CI users (GitHub Actions, GitLab), security teams using GitHub code scanning, and tools consuming the JSON.

### Why it's important or urgent?

Terminal output is the first impression at launch. JSON and SARIF formats become public contracts once people build on them, so they must be versioned from day one.

### What is the proposed solution?

`stealth doctor` gains `--format terminal|json|sarif` (terminal default, `--json` as a shortcut), `--output <file>`, and `--fail-under N` to exit non-zero when the overall score is below N.

| | terminal | `--json` | `--format sarif` |
|---|---|---|---|
| Audience | Humans | Scripts, agents, platform upload | GitHub code scanning |
| Content | Scores, top fixes, findings grouped by category | Full report incl. analyzer statuses | Findings + rules only |
| Schema | Not stable | Versioned (`schemaVersion: 1`) | SARIF 2.1.0 per ADR-003 |

### Technical approach

- **`core`**: `ReportRenderer` interface, with `TerminalRenderer`, `JsonRenderer` (Jackson) and `SarifRenderer` implementations
- **Terminal**: colour via picocli `Help.Ansi` (auto-detected, `--no-color`, honours `NO_COLOR`). Shows scores, the top 5 fixes, findings grouped by category/severity, analyzer failures and timings (`--verbose`)
- **JSON**: `schemaVersion`, tool version, scores, fix list, findings, analyzer results. Publish a JSON Schema in `docs/`
- **SARIF**: mapping per ADR-003 (`rules[]`, `level`, `security-severity`, `partialFingerprints`, `relatedLocations` for duplication). Non-file findings are anchored to the relevant `pom.xml` line
- **Exit codes**: `0` ok, `1` score below `--fail-under` (or the `fail-under` in `.stealth.yml`), `2` usage or runtime error. Failed or timed-out analyzers don't cause `1` on their own, but are printed as warnings
- Paths in JSON/SARIF are relative to the repo root, with forward slashes on all OSes

### Progress

- **Terminal output (done early, with the vulnerability analyzer):** `stealth doctor` groups findings by the line they point at, so each group is one fix (upgrading `spring-boot-starter-parent` covers its own outdatedness and every vulnerability that comes in through it), ranks fixes by worst severity then ADR-0002 weight, shows the top 10 with a summary per category, and `--all` lists every finding under its fix. Colours by severity on an ANSI terminal; plain symbols where stdout can't encode ✓ or →. See `FixList` and `TerminalReport` in `cli`. The score line comes with the scoring engine.
- **JSON, SARIF and `--fail-under` (done with `.stealth.yml`):** see the notes below.

### Notes from implementation

- **Renderers live in `core`** (`dev.stealth.core.report.JsonReport`, `SarifReport`) so the MCP server and the platform can reuse them; the terminal renderer stays in `cli` because it depends on picocli's `Ansi`. No `ReportRenderer` interface: the three take different inputs (SARIF needs the rule catalogue and the root build file), and an interface over one call site added nothing.
- **`--output FILE`** writes the chosen format to the file; with json/sarif the terminal report still goes to stdout, so a CI log shows the scores while the SARIF is uploaded. With SARIF on stdout, config warnings and the `fail-under` verdict go to stderr.
- **`--fail-under`** replaces `fail-under.overall` from `.stealth.yml`; category thresholds stay. A threshold the run can't judge (the overall score on a `--hygiene` run, or a category that was partial) exits 2 rather than passing silently.
- **SARIF details beyond ADR-0003's table:** `relatedLocations` for the other copies of duplicated code; when a repo has no root `pom.xml`, repository-level findings carry only a logical location (valid SARIF, but GitHub may not show them).
- **Show only flags** (`--critical`, …) narrow only the terminal list; JSON and SARIF always carry every finding, so code scanning doesn't close alerts because of a display filter.
- **Not done:** `--no-color` (picocli already honours `NO_COLOR` and non-TTY output) and `--verbose` timings; neither was needed for the CI gate.


### Definition of done

- [x] Snapshot tests per renderer on `fixtures/boot2-legacy` (`ReportSnapshotTest`; snapshots in `core/src/test/resources/snapshots/`)
- [x] SARIF output validated against the official SARIF 2.1.0 JSON schema in tests (`SarifReportTest`, `ReportSnapshotTest`; the ADR example too)
- [x] JSON schema published and output validated against it (`docs/schema/doctor-report.schema.json`)
- [x] Exit code tests for `--fail-under` (above, equal, below) and errors (`FailUnderGateTest`, `DoctorCommandTest`)
- [x] Windows path test (relative, forward slashes): `JsonReportTest.render_onEveryOs_pathsAreRepoRelativeWithForwardSlashes` runs a real analyzer, so it covers Windows on the CI matrix
- [x] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Uploading SARIF to GitHub / the GitHub Action: Phase 4
- HTML report: not planned for Phase 1

Suggested branch: `feature/CU-<clickup-id>_output_renderers_ci_gate`
Depends on: ADR-003, scoring engine
