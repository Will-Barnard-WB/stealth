# Contributing

Thanks for your interest! stealth is at a very early stage, so the most useful contributions right now are:
- Feedback on the [roadmap](ROADMAP.md), especially from anyone maintaining large Java/Spring codebases
- Real-world examples of tech or security debt that `stealth doctor` should catch
- Code, once the Phase 0 scaffold lands

## Before you start
- For anything beyond a small fix, open an issue first so we can agree on the approach.
- Security issues go through [SECURITY.md](SECURITY.md), not public issues.

## Development
You need JDK 21 or newer. Maven itself comes with the wrapper (`./mvnw`, or `mvnw.cmd` on Windows), so you don't need to install it.

| Command | What it does |
|---|---|
| `./mvnw verify` | Builds every module and runs unit tests (`*Test`, Surefire), integration tests (`*IT`, Failsafe) and the formatting check |
| `./mvnw spotless:apply` | Formats the code (google-java-format, AOSP style). Run it before committing if `verify` reports format violations |
| `./mvnw -pl cli -am test` | Tests one module plus the modules it depends on |
| `java -jar cli/target/stealth-cli-*.jar --help` | Runs the CLI you just built |
| `java -jar mcp/target/stealth-mcp-*.jar` | Runs the MCP server on stdio |

Modules:
- `core`: the analyzer SPI and `Finding` model. It must not depend on any other stealth module.
- `cli`: the `stealth` command (picocli + Spring Boot).
- `mcp`: the MCP server for AI agents (stdio).

Conventions:
- Dependency and plugin versions live only in the parent `pom.xml`, never in module POMs.
- Tests use JUnit 5, AssertJ and Mockito, with WireMock for HTTP. No containers.
- Name test methods `method_condition_expectedResult`, e.g. `analyze_cleanRepo_reportsNoFindings`.
- New analyzers need tests against the repos in `fixtures/`, including tests showing they *don't* flag clean code.

## Continuous integration
Every pull request and every push to `main` runs `./mvnw verify` on Ubuntu and Windows ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)). It also builds the release zip and runs the packaged launcher (`bin/stealth`, or `bin\stealth.bat` on Windows), so packaging problems show up before a release. The required check is called `ci`, and PRs can't merge until it passes. If a build fails, the Surefire/Failsafe reports are attached to the run as artifacts. Dependabot opens weekly PRs for Maven and GitHub Actions updates.

## Releasing
Releases are cut from `main` by pushing a `vX.Y.Z` tag. The `/release` skill in Claude Code prepares everything up to the tag. By hand, the steps are:

1. Add a `## [X.Y.Z] - YYYY-MM-DD` entry to `CHANGELOG.md`. The release notes are taken from this entry, and the release fails without it.
2. Set the version and check the build: `./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false && ./mvnw verify`
3. Commit (`Release vX.Y.Z`) and tag: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
4. Set the next development version (`X.Y.(Z+1)-SNAPSHOT`) and commit.
5. Push `main`, then the tag: `git push origin main && git push origin vX.Y.Z`

The tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml). It checks that the tag matches the POM version, runs `./mvnw verify`, and then JReleaser ([`jreleaser.yml`](jreleaser.yml)) does the rest:
- publishes a GitHub Release with the CLI as `.zip` and `.tgz` (`bin/stealth`, `bin/stealth.bat`, `lib/`) plus checksums
- updates the formula in [Will-Barnard-WB/homebrew-tap](https://github.com/Will-Barnard-WB/homebrew-tap), so users can run `brew install Will-Barnard-WB/tap/stealth`
- updates the manifest in [Will-Barnard-WB/scoop-bucket](https://github.com/Will-Barnard-WB/scoop-bucket), so Windows users can run `scoop install stealth` after `scoop bucket add stealth https://github.com/Will-Barnard-WB/scoop-bucket`. The manifest comes from [`src/jreleaser/distributions/stealth/scoop/manifest.json.tpl`](src/jreleaser/distributions/stealth/scoop/manifest.json.tpl)

Versions with a suffix such as `0.2.0-rc.1` are published as GitHub pre-releases.

To check a release locally without publishing anything, run `./mvnw package -DskipTests` and then `./mvnw -N jreleaser:assemble jreleaser:full-release -Djreleaser.dry.run=true`. This needs a regular clone: JReleaser can't read git worktrees.

## Pull requests
- Keep PRs focused on one change.
- Describe what changed and why, and how you tested it.
- By contributing, you agree your contribution is licensed under the [Apache License 2.0](LICENSE).
