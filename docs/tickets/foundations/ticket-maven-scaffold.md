# Maven multi-module scaffold (Java 21, Spring Boot)

ClickUp: [CU-869f96kfq](https://app.clickup.com/t/869f96kfq)

### What is the problem we are trying to solve?

The repo has no code yet. Every Phase 1 ticket (analyzers, scoring, CLI, MCP server) needs a buildable multi-module project with the agreed stack and test tooling in place, so work can start in parallel without each ticket re-deciding the setup.

### Why it's important or urgent?

It blocks all of Phase 1, plus the CI ticket. Getting dependency management, formatting and test conventions right now avoids churn across every later PR.

### What is the proposed solution?

A Maven multi-module project where `./mvnw verify` builds, formats and tests everything, and `stealth --version` runs from the built CLI.

### Technical approach

- **Parent POM**: Java 21, Spring Boot parent (latest 3.x), `groupId` from the name-availability ticket. All dependency and plugin versions managed here, none in module POMs
- **Maven wrapper** (`mvnw`, `mvnw.cmd`) committed
- **Modules** (all Spring Boot):
  - `core`: `Analyzer` SPI (`analyze(RepoContext) -> List<Finding>`), `Finding` record, placeholder only. No dependency on other modules
  - `cli`: picocli via `picocli-spring-boot-starter`, `stealth` root command with `--version` and `--help`, depends on `core`
  - `mcp`: Spring Boot app, empty stdio MCP server skeleton, depends on `core`
  - `rewrite`, `conventions` and `action` are **not** created yet (see Out of scope)
- **Testing**: JUnit 5, AssertJ, Mockito, `spring-boot-starter-test`, WireMock. Surefire runs `*Test`, Failsafe runs `*IT` in `verify`
- **Formatting**: Spotless (google-java-format, *proposed*, confirm in review) bound to `verify` via `spotless:check`
- **Other**: `.editorconfig`, `.gitattributes` (LF endings, `mvnw.cmd` CRLF) to stop line-ending churn between Windows and macOS/Linux
- **No Lombok, no containers** (no Testcontainers/Docker)
- **Risk**: picocli + Spring Boot startup time for a CLI. Measure `stealth --version` cold start and note it on the ticket. GraalVM native image is a later decision

### Definition of done

- [ ] `./mvnw verify` passes on a clean clone (Windows and macOS/Linux)
- [ ] `./mvnw spotless:check` fails on badly formatted code and `spotless:apply` fixes it
- [ ] `java -jar cli/target/*.jar --version` prints the version
- [ ] One example unit test per module (plain Mockito test in `core`, picocli `CommandLine.execute` test in `cli`) following the `method_condition_expectedResult` naming
- [ ] `core` has no dependency on `cli` or `mcp` (checked in `dependency:tree`)
- [ ] CONTRIBUTING "Development" section updated with the real commands

### Out of scope

- `rewrite`, `conventions`, `action` modules: created by their own phases so we don't carry empty modules
- CI workflow: separate ticket, depends on this one
- Real analyzers or MCP tools: Phase 1
- GraalVM native image: revisit after measuring startup time

Suggested branch: `feature/CU-869f96kfq_maven_scaffold`
Depends on: name availability (for `groupId`)
