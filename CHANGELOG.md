# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0-rc.2] - 2026-09-29
### Added
- Adds `stealth doctor [path]`, which runs every analyzer in parallel with per-analyzer timeouts (no analyzers ship yet, so it reports 0 findings)
- Adds Windows install via Scoop: `scoop bucket add stealth https://github.com/Will-Barnard-WB/scoop-bucket` then `scoop install stealth`

### Maintenance
- CU-869f96kz4 Adds fixture repos for analyzer precision tests
- CU-869f96kzk Marks ADRs 0001–0004 as accepted
- Upgrades Spring Boot to 4.1.1 and Spring AI to 2.0.1

## [0.1.0-rc.1] - 2026-09-29
### Added
- CU-869f96kfq Adds the `stealth` CLI skeleton (`--version`, `--help`) and an empty stdio MCP server

### Maintenance
- CU-869f96kyh Adds CI on Ubuntu and Windows and tag-driven releases to GitHub Releases and Homebrew
- CU-869f96kzk Adds design ADRs for the Finding model, scoring formula, SARIF mapping and `.stealth.yml`
