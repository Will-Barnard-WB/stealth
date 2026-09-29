# Duplication analyzer (PMD CPD)

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Copy-pasted code is classic tech debt, and AI agents make it worse by regenerating helpers that already exist. `stealth doctor` should report significant duplicated blocks so teams can see where it's accumulating.

### Who is impacted?

CLI and MCP users. It's also the basis for Phase 3's duplicate-intent detection.

### Why it's important or urgent?

It fills in the tech half of the score beyond dependencies, and it lays the CPD groundwork Phase 3 needs to catch AI-generated duplicates.

### What is the proposed solution?

A tech finding per group of duplicated code blocks above a size threshold, listing every location (file + line range) and the number of duplicated lines.

### Technical approach

- **`core`**: `DuplicationAnalyzer` (category tech) using PMD 7 CPD as a library (`CpdAnalysis` API, Java language module). PMD is BSD-licensed, which is fine for us
- Scan `src/main/java` of every module from `MavenProjectModel` (tests excluded by default, configurable). Skip generated sources (`target/`, `generated-sources`) and ignored paths
- Threshold: `duplication.minTokens`, default 100. Ignore literals and annotations to reduce noise. Keep identifiers so renamed copies aren't matched yet
- One finding per duplication group. Primary location = first occurrence, related locations = the rest (maps onto SARIF `relatedLocations`)
- Severity: low, medium above a larger-block threshold (final values per ADR-002)
- **Risk**: CPD memory and time on big repos. It runs under the runner's timeout, and we measure it on spring-petclinic and one large repo

### Definition of done

- [ ] Precision tests against `fixtures/duplicated`: blocks above the threshold are flagged, blocks below aren't, and `fixtures/boot4-clean` produces **no** findings
- [ ] Generated sources are excluded (test with a fake `target/generated-sources` file)
- [ ] Threshold configurable and tested
- [ ] Runtime on a large repo recorded on this ticket
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Semantic or renamed-identifier duplicates and "use the existing helper" suggestions: Phase 3
- Non-Java languages

Suggested branch: `feature/CU-<clickup-id>_duplication_analyzer`
Depends on: analyzer SPI, Maven model loading (module source roots), fixture repos
