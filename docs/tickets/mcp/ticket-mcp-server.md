# MCP server (local HTTP): repo_health, list_findings, check_dependency

ClickUp: _not yet created_

### What is the problem we are trying to solve?

AI coding agents (Claude Code, Cursor, Copilot) add and upgrade dependencies and write code with no view of a repo's health. They'll happily add an abandoned or vulnerable library. The doctor results need to be available to agents as tools they can call mid-task, not just to humans reading a terminal.

### Who is impacted?

Developers using AI agents in their IDE or terminal, and the agents themselves.

### Why it's important or urgent?

"Agent-native" is a core part of our positioning against Moderne and SonarQube, and it's half of what Phase 1 ships. `check_dependency` is the first tool that prevents debt rather than just reporting it.

### Decision: local HTTP server started by the CLI

Decided 2026-09-30. `stealth mcp` starts a long-running MCP server on the user's machine, and agents connect to it over HTTP. In MCP the client always connects to the server, so "connecting to Claude" means registering the server's URL with Claude Code, which `stealth mcp install` does.

| | Local HTTP (chosen) | stdio (not chosen for now) |
|---|---|---|
| Who starts it | The user runs `stealth mcp` (later: a background service) | Claude Code launches it per session |
| Speed | Warm: JVM startup, caches and scan results are paid for once and reused | Every session pays JVM startup (~2-3 s) and starts cold |
| Several agents at once | One server serves every Claude Code session, Cursor, etc. | One process per session |
| Costs | Must be running; port clashes; must be secured against other local processes and web pages | Nothing to manage |

stdio can still be added later for clients that only support it; the tools don't depend on the transport.

### What is the proposed solution?

```bash
stealth mcp              # starts the server on http://127.0.0.1:7331/mcp and keeps running
stealth mcp install      # registers it with Claude Code (and prints the Cursor config)
```

Tools:

| Tool | Input | Returns |
|---|---|---|
| `repo_health` | `path` | Scores (tech, security, overall), top fixes, analyzer statuses |
| `list_findings` | `path`, optional `category`, `severity`, `analyzer`, `limit` | Findings (ADR-001 shape), most severe first |
| `check_dependency` | `groupId`, `artifactId`, optional `version` | Latest stable version, last release date, known vulnerabilities and fixed version, maintenance status. Needs no repo |

Every tool that reads a repo takes `path`, so one server works for every repository on the machine.

### Technical approach

- **Transport**: Spring AI MCP server with the Streamable HTTP transport (MCP spec 2025-06-18), tools as `@Tool`-annotated methods on a Spring bean that delegates to `core` (`AnalyzerRunner`, `ScoringEngine`, `MavenCentralClient`, `OsvClient`)
- **Packaging**: a `stealth mcp` subcommand of the CLI, so one install (Homebrew, Scoop, zip) gives both. `cli` depends on `mcp`; `stealth mcp` boots the MCP application context with an embedded web server, while every other command stays non-web so their startup time doesn't grow
- **Port**: `127.0.0.1:7331` by default, `--port` to change it. If the port is taken, exit with a clear message (and say whether it's already a stealth server)
- **Security** (a localhost port is reachable by every local process, and by web pages through DNS rebinding):
  - Bind to `127.0.0.1` only, never `0.0.0.0`
  - Require a bearer token: generated on first run, stored in `~/.stealth/mcp-token` with owner-only permissions, and passed to Claude Code by `stealth mcp install` (`claude mcp add --transport http stealth http://127.0.0.1:7331/mcp --header "Authorization: Bearer <token>"`)
  - Validate the `Origin` and `Host` headers as the MCP spec requires; reject browser origins
  - Tools only read. Paths must be existing directories
- **`stealth mcp install`**: runs `claude mcp add` if the `claude` CLI is on `PATH` (user scope, so it works in every repo), otherwise prints the command; always prints the Cursor `mcp.json` snippet. Re-running updates the token
- **Caching**: keep the `DoctorReport` per (repo path, HEAD commit, working-tree dirty flag) for the life of the server, so `repo_health` followed by `list_findings` doesn't re-scan. Bound the cache (e.g. 20 repos, LRU)
- Respect `.stealth.yml` from the target repo
- Keep tool results compact (agents pay per token): cap `list_findings` at 50 by default, masked secrets only
- Tool descriptions written for agents: when to call each one (e.g. "call `check_dependency` before adding a Maven dependency")
- Logs go to `~/.stealth/logs/mcp.log` and the console; the transport isn't stdout, so logging is unrestricted
- **Risk**: the server isn't running when an agent session starts, and the agent just sees a failed MCP server. Mitigate with a clear `stealth mcp status`, and the README setup; a background service (launchd / systemd / Windows service) is a follow-up

### Definition of done

- [ ] Unit tests for each tool method (inputs, filtering, limits, error messages for bad paths/coordinates)
- [ ] Integration test that starts the server on a random port, connects an MCP client over Streamable HTTP, lists tools and calls each one against `fixtures/boot2-legacy` (remote APIs via WireMock)
- [ ] Security tests: missing or wrong token → 401; foreign `Origin` → 403; the server listens on 127.0.0.1 only
- [ ] `stealth mcp install` tested with the `claude` call stubbed: correct command and token when `claude` exists, printed instructions when it doesn't
- [ ] Port-in-use exits non-zero with a clear message
- [ ] Manually verified in Claude Code and one other MCP client (Cursor), with setup documented in README
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- stdio transport: add later if a client needs it
- Running as a background OS service (launchd, systemd, Windows service): follow-up ticket
- Access from other machines: the server is local-only by design
- Upgrade tools (`plan_upgrade`, `run_upgrade`): Phase 2
- Convention tools (`get_conventions`, `find_existing_helper`): Phase 3

Suggested branch: `feature/CU-<clickup-id>_mcp_server`
Depends on: analyzer SPI, scoring engine, freshness/maintenance/vulnerability analyzers (for `check_dependency`)
