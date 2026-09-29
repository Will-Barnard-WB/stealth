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

## Contributing
The project is at a very early stage. See [CONTRIBUTING.md](CONTRIBUTING.md), and the roadmap for where help is useful.

## Security
Please report vulnerabilities privately. See [SECURITY.md](SECURITY.md).

## License
[Apache License 2.0](LICENSE)
