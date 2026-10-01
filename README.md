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
mcp/           MCP server for AI agents
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
