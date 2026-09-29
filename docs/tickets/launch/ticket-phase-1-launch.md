# Phase 1 launch: docs, demo, Show HN, r/java, Spring community

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Phase 1 is meant to prove that people install `stealth doctor` and find it useful. That only happens if people can find it, install it in one command, understand it in 30 seconds, and see accurate results on their first run. A launch with visible false positives on a well-known repo would waste the one shot we get on Show HN.

### Who is impacted?

Java/Spring developers discovering the project, and early adopters and design partners.

### Why it's important or urgent?

It's Phase 1's success metric (installs, stars, weekly active repos) and it feeds the design-partner and waitlist work in the business track. It's timed for the end of Phase 1 (week 8).

### What is the proposed solution?

Validate the results by hand on well-known repos, polish install and docs, record a demo, then launch on Show HN, r/java and Spring community channels in a coordinated window.

### Technical approach

- **Precision pass first**: run `stealth doctor` on spring-petclinic plus two or three other popular open-source Spring repos (one multi-module, one Boot 2). Check every finding by hand, fix or suppress false positives, and record results in `docs/launch/precision-check.md`
- **Install**: Homebrew tap and GitHub Releases via the JReleaser pipeline, plus a one-line `curl` install or `jbang` option. Verify on a clean macOS, Linux and Windows machine
- **Docs**: README quickstart (install → `stealth doctor` → read the score), per-analyzer docs listing rule IDs, `.stealth.yml` reference, MCP setup for Claude Code and Cursor, CI example with `--fail-under` and SARIF upload
- **Demo GIF**: recorded with `vhs` from a checked-in tape file so it can be re-recorded. Shows doctor on petclinic and one MCP `check_dependency` call
- **Posts**: Show HN (Tuesday–Thursday morning US time), r/java, the Spring community (Spring blog/"This Week in Spring" submission, Spring Discord/Slack). Drafts reviewed in `docs/launch/`
- **Metrics**: baseline stars and waitlist before launch. Track stars, installs (release downloads), issues opened, and first-week false-positive reports
- **Risk**: a harsh first impression from one wrong finding. The precision pass is a hard gate, and someone is on call to triage issues for the first 48h

### Definition of done

- [ ] Precision check on spring-petclinic and at least two other repos, with every finding reviewed and zero known false positives left
- [ ] Clean-machine install verified on macOS, Linux and Windows
- [ ] README quickstart, analyzer docs, `.stealth.yml` reference, MCP setup and CI example published
- [ ] Demo GIF in README, generated from a checked-in `vhs` tape
- [ ] Release tagged and published via JReleaser, `./mvnw verify` green on the tag
- [ ] Show HN, r/java and Spring community posts published, with links recorded on this ticket
- [ ] First-week metrics and feedback summarized on this ticket

### Out of scope

- Landing page and waitlist: business track ticket (should be live before this)
- Paid tiers and pricing: later phases

Suggested branch: `chore/CU-<clickup-id>_phase_1_launch`
Depends on: all other Phase 1 tickets, CI and release pipeline
