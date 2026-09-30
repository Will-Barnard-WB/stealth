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

- [x] WireMock tests for `EndOfLifeClient` with recorded endoflife.date responses (`EndOfLifeClientTest`)
- [x] Precision tests: `fixtures/boot2-legacy` flags Spring Boot 2.7 and Java 11, `fixtures/boot4-clean` produces **no** EOL findings (`EndOfLifeAnalyzerTest`)
- [x] Unit tests for the "within 6 months" window using a fixed `Clock`
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **Rules:** `eol/past-end-of-life` (high) and `eol/approaching-end-of-life` (low), both covering Java and Spring
  Boot, because ADR-0001 gives each rule one default severity and severity here depends on lifecycle stage, not on
  product. Documented in `docs/rules/eol.md`. Confirm in review: rule ids are permanent once released.
- **Product is a fingerprint key, not part of the rule id**, as ADR-0001's table says, so `.stealth.yml` can't silence
  Java EOL separately from Spring Boot EOL except through the component allowlist. Revisit if that bites.
- **Upgrade target:** the ticket's "oldest supported version" is read as *the oldest cycle newer than the current one
  that is itself neither past EOL nor inside the warning window*, falling back to the oldest still-supported one. Plain
  "oldest supported" would recommend Java 8 (Temurin, supported to 2030) to a repo on Java 11.
- **Both support columns are used:** `eol` for the finding, `extendedSupport` for the "commercial support runs to X"
  sentence. endoflife.date returns either a date or a boolean for both, so `Support` is a sealed interface
  (`Until` / `Ended` / `Open`) rather than an `Optional<LocalDate>` that can't tell "over, no date" from "no end
  announced".
- **Config:** `StealthConfig.Eol` carries `javaDistribution` (default `eclipse-temurin`) and `javaVersion`. Nothing
  parses `.stealth.yml` yet, so these only have defaults at runtime; the config ticket fills them in.
- **JSON:** uses `tools.jackson.core:jackson-databind`, which the vulnerability analyzer added to `core` first. The
  response is read as a tree rather than bound to the record, because `eol` and `extendedSupport` are sometimes a date
  and sometimes a boolean.
- **Precision traps:** Java 11 is only flagged on `oracle-jdk`, not on the default Temurin, which is exactly why the
  distribution is configurable. `MavenModelLoader` already resolves both versions, so no model changes were needed.
- **Unknown or unreachable product fails the analyzer**, on the same reasoning as the freshness analyzer: a partial
  answer reads as "the rest are supported".

### Out of scope

- Other frameworks (Hibernate, Spring Framework on its own, Tomcat): follow-up once the pattern is proven
- Detecting the runtime JDK from Dockerfiles or CI config: later

Suggested branch: `feature/CU-<clickup-id>_eol_analyzer`
Depends on: Maven model loading, HTTP cache from the freshness analyzer
