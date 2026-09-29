# Move the scaffold to Spring Boot 4.1 and Spring AI 2.0

### What is the problem we are trying to solve?

The scaffold (CU-869f96kfq) is on Spring Boot 3.5.16, as its ticket asked ("latest 3.x"). Free (OSS) support for Boot 3.5 ended on 2026-06-30, so the project starts on an unsupported framework. `stealth doctor` will flag exactly this in other people's repos (Phase 1 "Java and Spring Boot versions that are end-of-life").

The MCP server is also behind: Spring AI 1.1.x, the last line that works with Boot 3.5, answers `initialize` with MCP protocol version `2024-11-05` even when the client asks for `2025-06-18`.

### Why it's important or urgent?

- A debt tool that runs on an end-of-life framework undermines trust at launch (Show HN, r/java). People will run `stealth doctor` on stealth itself.
- It gets harder with every PR: today the change is three POMs and about 10 classes. After Phase 1 it touches every analyzer, client and test.
- There are no Boot 3.x security patches without paid support.

### Signals

- endoflife.date: Boot 3.5 OSS EOL 2026-06-30. Boot 4.0 EOL 2026-12-31. **Boot 4.1 EOL 2027-07-31** (latest 4.1.1).
- MCP smoke test on the scaffold: `serverInfo` returned with `"protocolVersion":"2024-11-05"`.

### What is the proposed solution?

Move the parent POM to Spring Boot 4.1.x and the MCP module to Spring AI 2.0.x before any Phase 1 code lands. Nothing changes for users. `stealth --version`, `--help` and the MCP handshake behave the same.

### Technical approach

- Parent `pom.xml`: `spring-boot-starter-parent` 3.5.16 → 4.1.x, `spring-ai.version` 1.1.8 → 2.0.x
- **Risk: `picocli-spring-boot-starter` 4.7.7 is built against Boot 2.7.** It only contributes a `PicocliSpringFactory` (`IFactory`) bean through auto-configuration. If it breaks on Boot 4, drop the starter, depend on plain `picocli`, and declare the `IFactory` bean in `cli`: roughly a 10-line `@Configuration`
- Check that Boot 4's test changes (module split of `spring-boot-starter-test`, test slice packages) don't affect `CompositeAnalyzerTest`, `StealthCommandTest` or `McpServerApplicationIT`
- Check that the MCP properties in `mcp/src/main/resources/application.properties` (`spring.ai.mcp.server.*`) are unchanged in Spring AI 2.0
- WireMock is `wiremock-standalone` (shaded Jetty), so Boot 4's Jetty version shouldn't clash. Confirm with the first WireMock test
- Re-measure `stealth --version` cold start (baseline 1.3s on Boot 3.5, JDK 24, Apple silicon)

### Definition of done

- [ ] Parent POM on Spring Boot 4.1.x, MCP module on Spring AI 2.0.x
- [ ] `./mvnw verify` passes (tests + Spotless)
- [ ] `java -jar cli/target/stealth-cli-*.jar --version` prints the version; `--help` and unknown-option exit code 2 unchanged
- [ ] MCP `initialize` over stdio returns `serverInfo.name = "stealth"` and a current protocol version, with nothing else on stdout
- [ ] Cold start re-measured and noted on this ticket
- [ ] README/CONTRIBUTING mention the Boot version only where they already did

### Out of scope

- Java version bump: Java 21 stays the baseline (Boot 4.1 supports 17–26)
- New MCP tools: Phase 1

Suggested branch: `chore/CU-<clickup-id>_spring_boot_4`
Depends on: Maven multi-module scaffold (CU-869f96kfq)
