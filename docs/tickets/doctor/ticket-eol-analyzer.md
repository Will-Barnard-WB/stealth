# Framework and runtime end-of-life analyzer

ClickUp: _not yet created_

### What is the problem we are trying to solve?

`stealth doctor` doesn't tell users when the Java version or Spring Boot line they run is past (or close to) end of life. EOL runtimes and frameworks stop getting security patches, which is some of the most expensive debt a Spring estate carries, and it's what drives the Phase 2 upgrade work.

### Who is impacted?

CLI and MCP users, and design partners with large Spring Boot 2 estates in particular.

### Why it's important or urgent?

"You're on Spring Boot 2.7, which lost OSS support in 2023" is the single most persuasive finding for our target users, and it leads straight into `stealth upgrade` in Phase 2.

### What is the proposed solution?

A finding when the project's Java release or Spring Boot version is past end of life, and a lower-severity warning when EOL is within 6 months, showing the EOL date and the oldest supported version to move to.

### Technical approach

- **`core`**: `EndOfLifeClient` + `EndOfLifeAnalyzer` (category security, since EOL means no patches)
- Data from the endoflife.date API (`/api/spring-boot.json`, `/api/eclipse-temurin.json` by default for Java), through the shared HTTP cache (24h TTL)
- Versions come from `MavenProjectModel` (Spring Boot parent/BOM version, `maven.compiler.release` / `java.version`). Match on release cycle (`2.7`, `17`)
- Use the OSS support end date (`eol`). Mention commercial support (`extendedSupport`) in the message where the API provides it
- Java distribution is configurable in `.stealth.yml` (`eol.javaDistribution`), since EOL dates differ between Temurin, Corretto and Oracle
- Severity: past EOL → high, EOL within 6 months → low
- **Risk**: the compile target isn't necessarily the runtime Java version. Word the finding as "targets Java 11" and let `.stealth.yml` override the runtime version

### Definition of done

- [ ] WireMock tests for `EndOfLifeClient` with recorded endoflife.date responses
- [ ] Precision tests: `fixtures/boot2-legacy` flags Spring Boot 2.7 and Java 11, `fixtures/boot4-clean` produces **no** EOL findings
- [ ] Unit tests for the "within 6 months" window using a fixed `Clock`
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Other frameworks (Hibernate, Spring Framework on its own, Tomcat): follow-up once the pattern is proven
- Detecting the runtime JDK from Dockerfiles or CI config: later

Suggested branch: `feature/CU-<clickup-id>_eol_analyzer`
Depends on: Maven model loading, HTTP cache from the freshness analyzer
