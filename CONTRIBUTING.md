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

## Pull requests
- Keep PRs focused on one change.
- Describe what changed and why, and how you tested it.
- By contributing, you agree your contribution is licensed under the [Apache License 2.0](LICENSE).
