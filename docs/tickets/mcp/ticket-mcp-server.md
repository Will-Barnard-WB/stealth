# MCP server (stdio): repo_health, list_findings, check_dependency

ClickUp: _not yet created_

### What is the problem we are trying to solve?

AI coding agents (Claude Code, Cursor, Copilot) add and upgrade dependencies and write code with no view of a repo's health. They'll happily add an abandoned or vulnerable library. The doctor results need to be available to agents as tools they can call mid-task, not just to humans reading a terminal.

### Who is impacted?

Developers using AI agents in their IDE or terminal, and the agents themselves.

### Why it's important or urgent?

"Agent-native" is a core part of our positioning against Moderne and SonarQube, and it's half of what Phase 1 ships. `check_dependency` is the first tool that prevents debt rather than just reporting it.

### What is the proposed solution?

A stdio MCP server exposing three tools:

| Tool | Input | Returns |
|---|---|---|
| `repo_health` | `path` | Scores (tech, security, overall), top fixes, analyzer statuses |
| `list_findings` | `path`, optional `category`, `severity`, `analyzer`, `limit` | Findings (ADR-001 shape), most severe first |
| `check_dependency` | `groupId`, `artifactId`, optional `version` | Latest stable version, last release date, known vulnerabilities and fixed version, maintenance status. Needs no repo |

### Technical approach

- **`mcp`** module: Spring AI MCP server starter (stdio transport), tools as `@Tool`-annotated methods on a Spring bean that delegates to `core` (`AnalyzerRunner`, `ScoringEngine`, `MavenCentralClient`, `OsvClient`)
- **stdout is protocol only**: disable the Spring banner, send all logging to stderr, and fail tests if anything else writes to stdout
- Cache the `DoctorReport` per (repo path, HEAD commit, working-tree dirty flag) for the life of the server, so `repo_health` followed by `list_findings` doesn't re-scan
- Respect `.stealth.yml` from the target repo. Paths are validated to be existing directories
- Keep tool results compact (agents pay per token): cap `list_findings` at 50 by default, masked secrets only
- Tool descriptions written for agents: when to call each one (e.g. "call `check_dependency` before adding a Maven dependency")
- Distribution: a `stealth mcp` CLI subcommand, or a separate `stealth-mcp` launcher from the `mcp` jar. **Decide in review**. Document `claude mcp add` and Cursor config snippets
- **Risk**: JVM startup time on the first tool call. Measure and note it. The report cache makes later calls cheap

### Definition of done

- [ ] Unit tests for each tool method (inputs, filtering, limits, error messages for bad paths/coordinates)
- [ ] Integration test starting the server over stdio with an MCP client, listing tools and calling each one against `fixtures/boot2-legacy` (remote APIs via WireMock)
- [ ] Test that nothing but JSON-RPC is written to stdout
- [ ] Manually verified in Claude Code and one other MCP client, with setup documented in README
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- HTTP/SSE transport: stdio only for Phase 1
- Upgrade tools (`plan_upgrade`, `run_upgrade`): Phase 2
- Convention tools (`get_conventions`, `find_existing_helper`): Phase 3

Suggested branch: `feature/CU-<clickup-id>_mcp_server`
Depends on: analyzer SPI, scoring engine, freshness/maintenance/vulnerability analyzers (for `check_dependency`)
