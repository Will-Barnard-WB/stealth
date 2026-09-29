# Apply the final project name to groupId, packages and artifacts

### What is the problem we are trying to solve?

The scaffold (CU-869f96kfq) landed before "Check name availability" (CU-869f96geq) picked a name, so it uses the placeholder `dev.stealth` groupId, `dev.stealth.*` packages, `stealth-*` artifactIds and the `stealth` command name. The name ticket's DoD hands the groupId "to the scaffold ticket", but that ticket is already done, so the rename needs its own ticket.

### Why it's important or urgent?

The name has to be final before anything is published (Maven Central, GitHub Releases, Homebrew tap in the CI/release ticket) and before Phase 1 adds dozens of classes under `dev.stealth`. After the first release, a rename breaks installs and published coordinates.

### What is the proposed solution?

Once the name is chosen, rename the groupId, root package, artifactIds, CLI command name and MCP server name in one mechanical PR. If the final name happens to be `stealth` with the `dev.stealth` namespace, close this ticket with no changes.

### Technical approach

- POMs: `<groupId>dev.stealth</groupId>` in the parent, and the `stealth-core` dependency management entry and `<parent>` blocks in `core`, `cli` and `mcp`; artifactIds `stealth-parent`, `stealth-core`, `stealth-cli`, `stealth-mcp`; `<name>` and `<url>`
- Java packages: move `dev/stealth/{core,cli,mcp}` in `src/main` and `src/test` (use `git mv` so history follows)
- `StealthCommand` (`@Command(name = "stealth")`), `ManifestVersionProvider` output prefix, `StealthApplication` and the `StealthCommandTest` assertions
- `spring.ai.mcp.server.name` in `mcp/src/main/resources/application.properties`, and `McpServerApplicationIT`
- Docs: README, CONTRIBUTING (jar paths), ROADMAP, and the `.claude/skills` examples that mention `dev.stealth`
- Run `./mvnw spotless:apply` after the move; import order changes

### Definition of done

- [ ] No remaining `dev.stealth` / `stealth-` references except where the final name is still `stealth` (`git grep -n 'dev\.stealth'` is empty)
- [ ] `./mvnw verify` passes (tests + Spotless)
- [ ] `java -jar cli/target/<name>-cli-*.jar --version` prints `<name> <version>`
- [ ] MCP `initialize` returns `serverInfo.name = "<name>"`

### Out of scope

- Registering the domain, GitHub org and Maven Central namespace: covered by CU-869f96geq
- Renaming the GitHub repo itself: do it when the org exists

Suggested branch: `chore/CU-<clickup-id>_apply_final_name`
Depends on: Check name availability (CU-869f96geq), Maven multi-module scaffold (CU-869f96kfq)
