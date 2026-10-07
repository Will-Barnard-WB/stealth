# Stealth

**An autonomous maintainer for your repos: find, prevent and clear tech debt and security debt.**

> ⚠️ **Status: pre-alpha, planning stage.** Nothing is runnable yet. The commands below describe what's being built.
> See [ROADMAP.md](ROADMAP.md) for the plan and progress. `stealth` is a working name.

## Why

AI coding tools produce code faster than teams can maintain it. Duplicated helpers, drifting patterns, outdated
frameworks and unmaintained dependencies pile up. Each of these is tech debt, and many of them are also security risks.

stealth treats tech debt and security debt as **one backlog**, and uses automation (and optionally AI) to pay it
down at the same speed it's created.

## What it will do

| Command | What it does |
|---|---|
| `stealth doctor` | Gives the repo a health score across tech and security debt, with a ranked "fix these first" list |
| `stealth upgrade <recipe>` | Runs migrations and dependency upgrades (e.g. Spring Boot 2 → 3, Java 21), verified by your tests, on a new branch |
| `stealth learn` | Learns your repo's architecture and conventions |
| `stealth check --changed` | Checks new changes (yours or an AI agent's) against those conventions |
| `stealth hooks install` | Installs a Claude Code hook so agents get feedback when they break conventions |

It will also ship with:
- **An MCP server**, so AI agents (Claude Code, Cursor, Copilot) can ask about repo health, check a dependency or look up
  existing helpers *before* writing code.
- **A GitHub Action** that fails CI below a health score, and shows the debt each PR adds, split into AI-written and human-written.
- **SARIF output**, so findings appear in GitHub code scanning.

### What `doctor` checks (first version)
- Outdated dependencies and unmaintained ones (no release in over 2 years)
- Known vulnerabilities (via [OSV.dev](https://osv.dev)), including the version that fixes each one
- Java and Spring Boot versions that are end-of-life or close to it
- Secrets committed to the working tree
- Duplicated code
- Repo hygiene: stale branches, large files, missing CODEOWNERS/CI/tests

Java/Maven repos come first, then Gradle, then npm.

## Principles
- **Never touch your current branch.** Every change lands on a new branch or PR.
- **Verified by your tests.** Upgrades run your test suite before and after.
- **Useful without AI.** LLM features (like an automatic repair loop for failing tests) are opt-in.
- **Open core.** Everything that works on a single repo is free and Apache-2.0 licensed. See [docs/open-core.md](docs/open-core.md).

## Planned architecture

```
core/          analyzers, Finding model, scoring, registry and OSV clients
rewrite/       OpenRewrite integration and test verification harness
conventions/   learn/check engine (ArchUnit rules, helper index)
cli/           picocli command-line tool
mcp/           MCP server for AI agents (`stealth mcp`)
action/        GitHub Action
fixtures/      sample repos used in tests
```

Built with Java 21, Maven and picocli.

## Install

stealth needs Java 21. Homebrew installs it for you; on Windows, install it with Scoop as shown below.

macOS and Linux ([Homebrew](https://brew.sh)):

```sh
brew install Will-Barnard-WB/tap/stealth
```

On macOS, Homebrew needs up-to-date Command Line Tools to install from a tap. If it says they're outdated, update them in System Settings → General → Software Update, or run `xcode-select --install`.

Windows ([Scoop](https://scoop.sh)):

```powershell
scoop bucket add java
scoop install java/temurin21-jre   # skip if you already have Java 21
scoop bucket add stealth https://github.com/Will-Barnard-WB/scoop-bucket
scoop install stealth
```

Or download the `.zip` from [GitHub Releases](https://github.com/Will-Barnard-WB/stealth/releases), unpack it and add `bin/` to your `PATH`.

## Use it from Claude Code (MCP)

`stealth mcp` runs a local [MCP](https://modelcontextprotocol.io) server, so AI agents can check a repository or a
dependency themselves while they work. Ask Claude Code *"how are this repo's vulnerabilities looking?"* or *"is
log4j-core 2.14.1 safe to add?"* and it calls stealth and answers from the results.

```bash
stealth mcp install   # once: registers stealth with Claude Code for every repository
stealth mcp           # start the server and leave it running (Ctrl+C stops it)
stealth mcp status    # is it running?
```

Then start Claude Code in any Java/Maven repository (`/mcp` in Claude Code shows `stealth` as connected).

| Tool | What the agent gets | Typical question |
|---|---|---|
| `repo_health` | Security and tech scores, finding counts by severity, the top 5 fixes, analyzer statuses | "How healthy is this codebase?" |
| `list_findings` | Findings with location and fix, filtered by `category` (security/tech), minimum `severity`, `analyzer`, `limit` (default 50) | "What vulnerabilities do we have?" |
| `check_dependency` | Latest version, whether it's maintained, known vulnerabilities in a version and what fixes them. No repository needed | "Should I add commons-text 1.9?" |
| `plan_cleanup` | Proven version patches that fix vulnerabilities now, what needs review or a major, and what's left (see [`stealth clean`](#fix-vulnerabilities-stealth-clean)) | "Fix what you can without upgrading Spring Boot" |
| `apply_cleanup` | The safe patches committed to a new branch, tests run before and after | (after the user agrees to the plan) |
| `verify_cleanup` | What the agent's own changes resolved or introduced, compared with a base commit | "Did that fix it?" |
| `upgrade_impact` | Every place the code uses an API a dependency upgrade removes or deprecates, with what to use instead | "What would moving to Spring 6 break?" |

- **Fast follow-ups.** The first scan of a repository takes as long as `stealth doctor`; later calls reuse it until a file
  changes (git repositories), for up to an hour. `.stealth.yml` in the repository applies.
- **Local only.** The server listens on `127.0.0.1:7331` (`--port` to change; pass the same port to
  `install`). Clients need the token in `~/.stealth/mcp-token`, which `install` gives Claude Code; requests from web
  pages are refused. `stealth mcp install --rotate-token` replaces the token.
- **Without the `claude` command on PATH**, `install` prints the `claude mcp add` command to run instead. It always
  prints the `~/.cursor/mcp.json` entry for Cursor and other MCP clients.
- The server has to be running when Claude Code starts a session; if it isn't, `/mcp` shows stealth as failed. Start
  `stealth mcp` and reconnect.

## Fix vulnerabilities: `stealth clean`

Most CVEs in an older Spring Boot app come in through Spring Boot, and the usual advice ("upgrade to Spring Boot 3")
is a migration nobody can schedule this sprint. `stealth clean` finds what can be fixed now, without leaving your
framework's version, and proves it.

```bash
stealth clean                 # the plan: what can be fixed, proven; changes nothing
stealth clean --apply         # put the safe patches on a new branch, tests run before and after
stealth clean --verify        # compare your uncommitted changes with HEAD (or --base main)
stealth clean --impact org.springframework:spring-web:6.1.14   # what that upgrade breaks here, line by line
```

- **The cheapest edit for each vulnerable dependency:** its own `<version>` line when you declare it; else the
  property your parent manages it with (`<tomcat.version>`), or the version property of the BOM the parent imports
  (`<spring-framework.version>`, which moves the whole family together); else a `<dependencyManagement>` pin.
- **Proven before anything is written:** each patch is applied to a scratch copy of the POMs, the dependency tree is
  re-resolved and checked against OSV again, and it's kept only if its vulnerabilities are gone, none are introduced,
  and the new version actually resolves. (OSV sometimes names commercial-only releases, such as Spring 5.3.42, as the
  fix; those are skipped.)
- **Safe by default:** patch releases of versions your framework manages (Tomcat 9.0.83 → 9.0.121) and bumps of
  dependencies you declare. Minor jumps past what the framework manages (Logback 1.2 → 1.5 under Spring Boot 2.7) are
  proven but listed for review (`--allow-minor`); new majors need `--allow-major`.
- **Your checkout is never touched:** `--apply` refuses uncommitted changes, works in a temporary git worktree on a
  new `stealth/clean-<date>-<time>` branch, commits one patch per commit, runs your tests (`./mvnw -B test`, `mvn`,
  or `--test-command`) before and after, and drops any patch that makes them worse.

**Upgrade impact** (`--impact`, MCP `upgrade_impact`) is for the upgrades that aren't drop-ins. It compares the
library's public API between the version you use and the target (with ASM, from the jars), then finds every place
your compiled code calls, extends or overrides something removed or deprecated, with `file:line` and what to use
instead: for `boot2-legacy`, spring-web 5.3 → 6.1 removes 520 APIs, and the one that matters is
`RequestIdFilter.java:20`, an override of `doFilterInternal` that now takes `jakarta.servlet` types. Exact when the
project is compiled; otherwise it matches imports only, and says so.

On `fixtures/boot2-legacy` (Spring Boot 2.7.18) the safe patches clear 47 of 108 known vulnerabilities in one
command, on a reviewable branch, with the tests green; the reviewed ones take it to 76. Most of the rest have no fix
in the open-source Spring 5.3 / Boot 2.7 lines.

## Guard the agent while it works: `stealth hooks`

```bash
stealth hooks install            # adds hooks to this repo's .claude/settings.json (commit it for the team)
stealth hooks install --user     # or to ~/.claude/settings.json, for every repository
stealth hooks uninstall
```

Claude Code then runs stealth automatically, with no prompting:

- **After each edit** (`Edit`, `Write`, `MultiEdit`): if a `pom.xml` gained or changed a dependency, it's checked
  against Maven Central and OSV. A vulnerable version, or an artifact that doesn't exist (an invented name an attacker
  could register), is reported straight back to the agent with the version to use. Any other edited file is scanned
  for hardcoded secrets.
- **Before it finishes** (`Stop`): the same checks over every file the session changed, so it doesn't hand back work
  that added a vulnerable dependency or a secret.

Only what the agent introduced is reported, compared with the last commit; existing debt is `stealth doctor`'s job.
The agent is told to fix it, or to tell you why it's intended (for example a version you asked for). Each check takes
about a second, a Stop check is never repeated in the same turn, and if stealth can't run, the agent carries on.
`.stealth.yml` applies (`ignore`, `allow.secrets`).

## Output formats and the CI gate

```bash
stealth doctor                                   # terminal report: scores and the top fixes
stealth doctor --json > stealth.json             # the whole report, for scripts and agents
stealth doctor --format sarif -o stealth.sarif   # for GitHub code scanning; terminal report still on stdout
stealth doctor --fail-under 70                   # exit 1 if the overall score is below 70
```

| | terminal | `--json` | `--format sarif` |
|---|---|---|---|
| For | People | Scripts, agents, platform upload | GitHub code scanning (and other SARIF viewers) |
| Contains | Scores, the top fixes (`--all` for every one) | Scores with deductions, every fix and finding, analyzer statuses, warnings | Findings and their rules, the score in `run.properties` |
| Stable shape | No | [`docs/schema/doctor-report.schema.json`](docs/schema/doctor-report.schema.json), `schemaVersion: 1` | SARIF 2.1.0, mapped as [ADR-0003](docs/adr/0003-sarif-mapping.md) says |

Paths in JSON and SARIF are relative to the repository root, with forward slashes on every OS. The Show only flags
(`--critical`, `--high`, …) narrow the terminal list only; JSON and SARIF always have every finding.

**Exit codes:** `0` the run finished (and passed any `fail-under`); `1` a score is below its `fail-under` threshold;
`2` usage or configuration error, or a `fail-under` that can't be judged (for example `--fail-under` with `--hygiene`,
which leaves the security score out). An analyzer that fails or times out doesn't change the exit code on its own;
the report says which one and that its findings are missing.

To upload to GitHub code scanning from a workflow:

```yaml
- run: stealth doctor --format sarif --output stealth.sarif --fail-under 70
- uses: github/codeql-action/upload-sarif@v4
  if: always()
  with:
    sarif_file: stealth.sarif
    category: stealth
```

## Configuration: `.stealth.yml`

An optional `.stealth.yml` at the repository root tunes `stealth doctor` for that repo. It's committed, so changes to it
are reviewed like code. `stealth doctor --config FILE` reads another file instead. The full format is
[ADR-0004](docs/adr/0004-stealth-yml-format.md), and [`docs/schema/stealth-yml.schema.json`](docs/schema/stealth-yml.schema.json)
gives editors autocompletion (in VS Code with the YAML extension, add `# yaml-language-server: $schema=<url>` at the top).

```yaml
version: 1

# Paths no analyzer looks at: gitignore-style globs, relative to the repo root.
# A glob matching a Maven module directory excludes that module and its dependencies.
ignore:
  - "legacy/**"
  - "**/generated/**"

# Override a rule's severity (critical, high, medium, low, info), or turn it off.
severity:
  deps/outdated-patch: off
  duplication/cpd: info

# Accepted findings. Each entry needs a reason; expires (UTC) is optional.
allow:
  secrets:
    - path: "src/test/resources/**"
      reason: Test fixtures, not real keys
  dependencies:
    - component: "pkg:maven/commons-collections/commons-collections"   # no @version = any version
      rules: [maintenance/no-recent-release]
      reason: Only used by the legacy importer, being removed
      expires: 2026-12-31
    - advisory: GHSA-599f-7c49-w659                                    # or a CVE alias
      reason: Not reachable from untrusted input
      expires: 2026-11-30

# Per-analyzer settings. Every analyzer accepts enabled: false.
analyzers:
  maintenance: { stale-after: P3Y }                    # default P2Y
  duplication: { min-tokens: 150, include-tests: true } # defaults 100, false
  hygiene: { stale-branch-after: P180D, large-file-bytes: 10485760 }  # defaults P90D, 5 MB
  secrets: { enabled: true }

# Which JDK's end-of-life dates apply, and the Java the repo runs on if it isn't the compile target.
eol:
  java-distribution: oracle-jdk   # default eclipse-temurin
  java-version: "17"

# CI gate: exit code 1 when a score is below its minimum. A single number applies to the overall score.
fail-under:
  overall: 70
  security: 80
```

| Key | Meaning |
|---|---|
| `version` | Format version, `1`. Missing is read as 1 with a warning; a higher version needs a newer stealth. |
| `ignore` | Globs of paths excluded from every analyzer, before they run. |
| `severity` | Rule id → `critical`, `high`, `medium`, `low`, `info` or `off`. Rule ids are listed in [`docs/rules/`](docs/rules). |
| `allow.secrets` | Accepted secrets, by `path` glob and/or `fingerprint`, optionally limited to `rules`. |
| `allow.dependencies` | Accepted dependency findings, by `component` (Package URL) and/or `advisory` (id or alias), optionally limited to `rules`. |
| `allow.*[].reason` / `expires` | Why it's accepted, and the last day it applies. Expired entries stop applying and print a warning. |
| `analyzers.<id>.enabled` | `false` turns an analyzer off for this repo. |
| `analyzers.maintenance.stale-after` | How old the newest release can be (ISO-8601 period). |
| `analyzers.duplication.min-tokens` / `include-tests` | Smallest duplicated block reported; whether `src/test/java` is scanned. |
| `analyzers.hygiene.stale-branch-after` / `large-file-bytes` | When a branch counts as stale; when a file counts as large. |
| `eol.java-distribution` / `java-version` | See [docs/rules/eol.md](docs/rules/eol.md). |
| `fail-under` | Minimum overall score, or a map of `overall` / `security` / `tech`. `--fail-under N` replaces `overall`. |

Unknown keys and rule ids print a warning with the line number (and a "did you mean"), so a newer file still works with
an older stealth. An invalid value for a known key, such as `fail-under: 120`, stops the run with exit code 2, so a broken
CI gate can't pass by accident. camelCase spellings (`javaDistribution`) are accepted too.

## Contributing
The project is at a very early stage. See [CONTRIBUTING.md](CONTRIBUTING.md), and the roadmap for where help is useful.

## Security
Please report vulnerabilities privately. See [SECURITY.md](SECURITY.md).

## License
[Apache License 2.0](LICENSE)
