# Analyzer SPI and parallel runner

ClickUp: _not yet created_

### What is the problem we are trying to solve?

`stealth doctor` will run eight or more analyzers, some network-bound (Maven Central, OSV.dev, endoflife.date) and some CPU-bound (CPD, secrets, JGit). Without a shared contract and runner, each analyzer would make its own choices about threading, timeouts and error handling, and one slow or crashing analyzer could hang or kill the whole run.

### Who is impacted?

Every Phase 1 analyzer ticket builds on this, and so do the scoring engine, renderers and MCP server that consume its output.

### Why it's important or urgent?

It's the first Phase 1 ticket and blocks all analyzer work. A doctor run that hangs on a network timeout or dies on one bad file destroys trust on first use.

### What is the proposed solution?

A small `Analyzer` interface in `core`, and a runner that executes all enabled analyzers in parallel with per-analyzer timeouts. The result is a report: findings plus a status for each analyzer (ok / failed / timed out / skipped). `stealth doctor [path]` runs it end to end, with placeholder output until the renderers ticket lands.

### Technical approach

- **`core`**:
  - `Analyzer` interface: `id()`, `category()` (tech/security), `analyze(RepoContext) -> List<Finding>`
  - `RepoContext`: repo root, loaded `.stealth.yml` (defaults until the config ticket), and lazily loaded shared resources (Maven project model, JGit `Repository`) so analyzers don't each re-parse the POMs
  - `Finding` record as defined in ADR-001
  - `AnalyzerRunner`: runs each analyzer on a virtual-thread executor with a per-analyzer timeout (default 60s, configurable), catches exceptions, and returns `DoctorReport(findings, List<AnalyzerResult>)`, where `AnalyzerResult` holds id, status, duration and error message
  - Analyzers are Spring beans, injected as `List<Analyzer>` and filtered by config (enabled/disabled)
- **`cli`**: `stealth doctor [path]` subcommand (defaults to cwd), with a basic summary until the renderers ticket
- An analyzer failure never fails the run. It shows up in the report, and the scoring engine treats that category as incomplete
- **Risk**: shared lazy resources must be thread-safe. Use memoized suppliers initialized once

### Definition of done

- [ ] Unit tests for `AnalyzerRunner`: parallel execution, a timeout marks the analyzer `TIMED_OUT` without blocking others, an exception marks it `FAILED`, and disabled analyzers are `SKIPPED`
- [ ] `stealth doctor fixtures/boot4-clean` runs with a stub analyzer and exits 0
- [ ] `Finding` matches ADR-001
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Real analyzers: one ticket each
- Output formats and exit codes: renderers ticket
- Progress bars and a spinner: later polish

Suggested branch: `feature/CU-<clickup-id>_analyzer_spi_runner`
Depends on: Maven scaffold, ADR-001 (Finding model)
