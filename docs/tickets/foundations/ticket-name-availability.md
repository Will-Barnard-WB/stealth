# Check name availability and pick the final project name

ClickUp: [CU-869f96geq](https://app.clickup.com/t/869f96geq)

### What is the problem we are trying to solve?

`stealth` is a placeholder name. Before the scaffold fixes a Maven `groupId`, root Java package, CLI binary name and Homebrew tap, we need a name that's actually available everywhere we publish. Renaming after Phase 1 launches (Show HN, r/java) would break installs and waste launch momentum.

### Why it's important or urgent?

It blocks the Maven scaffold: the `groupId` and root package come from the name. Changing them later means touching every file and republishing artifacts. Maven Central namespaces also require domain or GitHub-org verification, which takes time.

### What is the proposed solution?

Shortlist 3–5 candidate names, check each against every place we'll publish, and pick one with all of them free (or a clear fallback).

### Technical approach

For each candidate check:
- **GitHub org** `github.com/<name>` is free
- **Maven Central namespace**: `io.<name>` / `dev.<name>` is unclaimed, and we can verify it (needs the domain or the GitHub org)
- **Domain**: `.dev` / `.io` / `.com` availability and price
- **Homebrew**: no existing formula/tap called `<name>` (`brew search <name>`)
- **CLI binary**: no clash with common tools on `$PATH` (Linux/macOS/Windows)
- **npm / PyPI / Docker Hub**: name free, for later (MCP server packaging, Docker image)
- **Trademark**: quick search for obvious conflicts in developer tooling (EUIPO / USPTO)

Record the chosen `groupId` and root package (e.g. `dev.<name>`) for the scaffold ticket.

### Definition of done

- [ ] Shortlist checked against every item above, results recorded in a table on this ticket
- [ ] Final name chosen
- [ ] GitHub org and domain registered
- [ ] Maven Central namespace claimed and verified
- [ ] `groupId` and root package recorded and passed to the Maven scaffold ticket
- [ ] README/ROADMAP updated if the name changes

### Out of scope

- Logo and branding: comes with the landing page in the go-to-market track
- Renaming the GitHub repo: do it after the scaffold lands, in one go
