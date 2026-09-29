# Decide how to make CLI startup fast enough for hooks (CDS/AOT vs GraalVM native image)

### What is the problem we are trying to solve?

The scaffold ticket (CU-869f96kfq) flagged picocli + Spring Boot startup time as a risk and deferred GraalVM native image "until measured". It's now measured: `stealth --version` takes **about 1.3s cold** on the scaffold (Boot 3.5.16, JDK 24, Apple silicon), and that command does no work. Real commands will add analyzer beans and HTTP clients on top.

1.3s is fine for `stealth doctor`, which runs for many seconds anyway. It's too slow for Phase 3's `stealth check --changed` Claude Code hook, which runs on every agent edit, and it makes the CLI feel sluggish.

### Who is impacted?

- Claude Code / AI-agent users of the Phase 3 hook (runs on every edit)
- CLI users running quick commands (`--version`, `--help`, `check --changed`)
- The MCP server starts once per session, so it matters less

### Why it's important or urgent?

It isn't urgent. It needs a decision before Phase 3 hooks ship, and ideally before Phase 1 packaging (JReleaser, Homebrew), because a native binary changes how releases are built and distributed.

### Signals

- Scaffold measurement: `time java -jar cli/target/stealth-cli-0.1.0-SNAPSHOT.jar --version` → real 1.30–1.34s over 3 runs

### What is the proposed solution?

Measure the cheap JVM options against a native image, then pick one based on hook latency, with build and distribution cost as the trade-off.

### Technical approach

Measure `--version` and a representative Phase 1 command on each option:
- **Baseline JVM** (current)
- **JVM tuning:** `spring.main.lazy-initialization=true`, `-XX:TieredStopAtLevel=1`, `-Xshare` CDS archive
- **Spring AOT + CDS / Project Leyden AOT cache** (JDK 24+ `-XX:AOTCache`): no code change, JVM distribution stays
- **GraalVM native image** via `spring-boot-maven-plugin` `native` profile: fastest start, but needs reflection hints for picocli, Jackson and OpenRewrite (Phase 2 may be hard), per-OS builds in CI, and longer build times

Record results in an ADR (`docs/adr/`) with the decision.

### Definition of done

- [ ] Startup measured for each option on macOS and Windows, results in a table on this ticket
- [ ] ADR written with the chosen approach and a target (e.g. < 300ms for `check --changed`)
- [ ] If a build change is chosen, a follow-up ticket for it (CI matrix / JReleaser impact noted)

### Out of scope

- Implementing the native build or CI changes: follow-up ticket once decided
- MCP server startup: long-lived process, revisit only if agents complain

Suggested branch: `spike/CU-<clickup-id>_cli_startup`
Depends on: Maven multi-module scaffold (CU-869f96kfq); ideally after the Spring Boot 4 upgrade so numbers reflect the real stack
