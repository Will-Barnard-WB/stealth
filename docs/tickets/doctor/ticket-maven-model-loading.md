# Maven model loading (effective versions, parent POMs, BOMs, multi-module)

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Almost no real Spring project declares versions directly. They come from `spring-boot-starter-parent`, imported BOMs, `${property}` placeholders and parent POMs in multi-module builds. If we read `pom.xml` naively, freshness, vulnerability and EOL findings are wrong or missing, and that's the fastest way to lose a user's trust.

### Who is impacted?

Every dependency-based analyzer (freshness, maintenance, vulnerability, EOL) and every user with a multi-module Maven repo.

### Why it's important or urgent?

Every dependency analyzer depends on it. Wrong effective versions produce false positives (flagging a version that's actually managed newer) and false negatives (missing a vulnerable transitive version).

### What is the proposed solution?

A shared `MavenProjectModel`, built once per run and exposed through `RepoContext`. It gives each module's effective dependencies with their resolved versions and where each version was declared, so findings can point at the right line in the right `pom.xml`.

### Technical approach

- **`core`**, new `maven` package:
  - Use `maven-model-builder` with `ModelBuildingRequest.setLocationTracking(true)` to build the effective model per module, including parent and BOM import resolution and property interpolation
  - Walk `<modules>` recursively from the root POM
  - Resolve remote parents and BOMs through `maven-resolver` against Maven Central, using `~/.m2/repository` first. `--offline` uses only the local repo and records unresolved parents as a warning
  - Collect the dependency tree per module (maven-resolver `collectDependencies`) so the vulnerability analyzer can see transitive dependencies and the path that pulls them in
  - Model: `MavenProjectModel(List<MavenModule>)`, `MavenModule(gav, pomPath, directDeps, dependencyTree, properties)`, `ResolvedDependency(ga, version, scope, declaredAt: file+line, managedBy)`
  - Also extract the Java release (`maven.compiler.release`, `source`/`target`, `java.version`) and the Spring Boot version for the EOL analyzer
- De-duplicate across modules: a dependency managed in the parent is reported once, at its declaration site
- **Risk**: resolving parents/BOMs needs network, which is slow on first run. Share the resolver session and cache across the run
- **Risk**: dependencies that can't be resolved (private repos). Honour `~/.m2/settings.xml` mirrors/servers where maven-resolver supports it, otherwise skip with a warning and never crash

### Definition of done

- [ ] Tests against `fixtures/multi-module`: correct effective versions per module for parent-managed, BOM-managed and property-defined dependencies
- [ ] Tests against `fixtures/boot2-legacy`: Spring Boot and Java versions extracted, and starter versions resolved from the Boot parent
- [ ] `declaredAt` points at the line that actually sets the version (parent or `dependencyManagement`), not the usage site
- [ ] Offline mode with an empty local repo degrades to warnings and doesn't throw
- [ ] Remote calls in tests go through WireMock or a pre-seeded local repo, never the real Central
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Gradle builds: Phase 1 is Maven only
- Private repository authentication beyond what `settings.xml` + maven-resolver give us for free

Suggested branch: `feature/CU-<clickup-id>_maven_model_loading`
Depends on: analyzer SPI (for `RepoContext`), fixture repos
