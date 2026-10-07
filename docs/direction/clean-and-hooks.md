# Where stealth goes next: `stealth clean` and agent hooks

**Status:** proposal for review · **Date:** 2026-10-06 · **Builds on:** `stealth doctor`, the MCP server (#23)

This proposes the next two pieces of stealth: `stealth clean` (MCP tools plus CLI) and Claude Code hooks. It's for
review before we write code, so it explains *why* as much as *what*. The questions we most want answered are at the
end.

## Contents

1. [Where we are](#1-where-we-are)
2. [Who this is for](#2-who-this-is-for)
3. [The principle: the agent changes code, stealth proves it](#3-the-principle-the-agent-changes-code-stealth-proves-it)
4. [`stealth clean`: the MCP tools](#4-stealth-clean-the-mcp-tools)
5. [The hooks: prevent new debt while the agent works](#5-the-hooks-prevent-new-debt-while-the-agent-works)
6. [How the pieces fit together](#6-how-the-pieces-fit-together)
7. [Why we're the ones to build this](#7-why-were-the-ones-to-build-this)
8. [Risks and how we contain them](#8-risks-and-how-we-contain-them)
9. [Delivery order](#9-delivery-order)
10. [Questions for review](#10-questions-for-review)

---

## 1. Where we are

Phase 1 is built. `stealth doctor` finds tech and security debt in Java/Maven repos, which covers known
vulnerabilities, outdated or unmaintained dependencies, end-of-life Java and Spring Boot, secrets, duplication and
hygiene. It scores the repo and ranks fixes. The MCP server (`stealth mcp`) exposes that to coding agents through
`repo_health`, `list_findings` and `check_dependency`, and Claude Code already uses it to answer "how are this repo's
vulnerabilities looking?".

What's missing is the step after the diagnosis. Today the agent knows *what* is wrong and is left to work out *how* to
fix it. That's exactly where agents are weakest on Java codebases:

- **They guess versions.** They pin whatever they remember from training data, which is often old and sometimes
  vulnerable.
- **They can't see the resolved dependency tree.** Most CVEs in a Spring Boot app arrive transitively. The agent edits
  `pom.xml` without knowing what Maven will actually resolve.
- **They don't know what an upgrade breaks** in *this* codebase until compilation fails, and then they patch
  symptoms.
- **They declare victory without proof.** "I've upgraded the dependency" isn't the same as "the CVE is gone, the build
  is green and nothing else got worse".

On `fixtures/boot2-legacy`, doctor reports 108 security findings, and 103 of them come in through one line:
`spring-boot-starter-parent 2.7.18`. The obvious advice is "upgrade to Spring Boot 4.1", which is a multi-week
migration. Neither doctor nor an agent can say today what the team could safely do *this week*.

## 2. Who this is for

**Java teams that use AI coding agents (Claude Code, Cursor, Copilot) on existing Spring Boot services**, particularly
older ones on Boot 2.x, Java 8–17, with CVE pressure from a security scanner or compliance deadline.

What their week looks like:

- A scanner (Dependabot, Snyk, the security team's tool) reports dozens of CVEs, most of them transitive through
  Spring Boot.
- The scanner's fix is "upgrade Spring Boot to 3.x". That's `javax` → `jakarta`, Spring Security config rewrites and
  Java 17. Nobody can schedule it this sprint.
- So the backlog sits, or someone hand-pins versions in the POM, hopes, and finds out in staging.
- Meanwhile their agents add new dependencies and code every day, sometimes vulnerable versions or hardcoded
  credentials, because nothing checks the agent's work as it happens.

We deliberately chose not to target "vibe coders" first. They mostly build in JavaScript and Python, need zero-setup
tools, and wouldn't ask for repo health. Java teams have the pain, the budget, and agents in daily use.

## 3. The principle: the agent changes code, stealth proves it

`doctor` already says what's wrong. If `clean` just restated findings as a to-do list, it would duplicate
`repo_health`, and a capable agent can grep a repo by itself anyway. So `clean` does only the work an agent **can't**
do cheaply or reliably, and leaves judgement and code edits to the agent:

| Who | Does |
|---|---|
| **stealth** | Deterministic, checkable work: compute version fixes and **prove them on the resolved dependency tree**; apply those proven patches on a new branch with the tests as a gate; diff library APIs against the repo's actual calls; measure test coverage of risky lines; verify the agent's work afterwards |
| **The agent** | Code changes that need judgement: migrating call sites, replacing an abandoned library, writing tests, moving a secret into configuration, explaining the diff to the developer |
| **The developer** | Decisions: approving the plan, rotating credentials, choosing replacements, merging |

Two consequences:

- **stealth never edits the developer's working tree.** Its only writes are proven patches, committed to a new
  `stealth/clean-…` branch built in a temporary git worktree. The current branch, uncommitted files and the IDE are
  untouched. This is the README's first principle ("never touch your current branch") made concrete.
- **Every step ends in a pass/fail check stealth can compute:** CVE gone from the resolved tree, compiles, tests pass,
  lines covered, no new findings. That's what turns an agent's "done" into evidence.

## 4. `stealth clean`: the MCP tools

All tools live on the existing `stealth mcp` server: same localhost-only server, same token, same `path` argument and
scan cache. Any agent registered with `stealth mcp install` gets them with no new setup. The CLI `stealth clean` is
the same engine, for people and CI.

| Tool | Writes? | One-line purpose |
|---|---|---|
| `plan_cleanup` | no | What can be fixed now without a migration (proven), what needs a migration (sized), and what needs a human |
| `apply_cleanup` | new branch only | Apply the proven patches on `stealth/clean-…`, tested, one commit per patch |
| `verify_cleanup` | no | Did it work? Compiles, tests pass, targeted findings gone, nothing new |
| `upgrade_impact` | no | For a non-drop-in upgrade: the exact call sites in this repo that use APIs that changed |
| `test_gaps` | no | Which of those call sites no test runs, and what test to write |
| `verify_tests` | no | Do the agent's new tests pass on today's code, and do they actually run those lines? |

### 4.1 `plan_cleanup`: patch without migrating

**What it returns:** three lists.

- **Proven patches.** Version changes that clear CVEs *without* a framework major. Example:
  > Set `<tomcat.version>` to the newest 9.0.x (overrides the 9.0.83 managed by Spring Boot 2.7.18). Targets the 36
  > tomcat-embed-core CVEs doctor reports on `boot2-legacy`. Resolved tree re-checked: no new CVEs. Same major.
- **Needs a migration.** What remains, sized with `upgrade_impact`, e.g. *Spring Boot 2.7 → 3.x: 41 call sites in 12
  files (`javax.servlet` → `jakarta.servlet`: 38; `WebSecurityConfigurerAdapter` removed: 3).*
- **Needs a human.** Secrets (rotate them, then move them out of code), abandoned libraries (choose a replacement),
  each with location and evidence.

**How the patch search works.** For each vulnerable artifact in the resolved tree, try fixes from cheapest to most
invasive:

1. **Bump the declared version within its major.** For a direct dependency, `VersionLocator` already gives the exact
   line: `<version>` or the property it uses.
2. **Override the property the parent manages it with.** Spring Boot's BOM defines most versions as properties
   (`tomcat.version`, `snakeyaml.version`, `jackson-bom.version`, …). We already trace managed versions back to the
   parent POM (`VersionLocator.locateExternal`). We read the property name there and set it in the user's POM. One
   line, and it's the supported way to override Boot's versions.
3. **Pin it in the user's `<dependencyManagement>`** when there is no property.
4. **Move the parent or BOM to its newest version within the same major** (e.g. Boot 2.7.18 → the last 2.7.x).

**Proof before anything is written.** Each candidate is applied to a scratch copy of the POMs. The dependency tree is
re-resolved with our existing `MavenModelLoader` and re-checked against OSV. A candidate is kept only if the target
CVEs are gone **and** nothing new appeared, such as a different vulnerable transitive version pulled in by the
change. Same-major changes are marked safe; cross-major overrides (e.g. snakeyaml 1.x → 2.x under Boot 2.7) are marked
risky and never applied by default.

**Why it's needed.** This is the gap between the scanner ("upgrade Boot") and what a team can ship this week.
Overriding Boot-managed properties is well known to experienced Spring developers. Scanners and Dependabot rarely do
it, and agents don't do it reliably because they can't see the resolved tree. Being able to say *"cleared N CVEs,
still on Boot 2.7, one-line diffs, proven on the resolved tree, tests green"* is the clearest value we can show.

### 4.2 `apply_cleanup`: proven patches on a branch

**What it does:**

1. Refuses unless the repo is git and the working tree is clean.
2. Runs `git worktree add -b stealth/clean-<timestamp>` in a temporary directory.
3. Runs the tests on the untouched code as a baseline (`./mvnw -B test`, `mvnw.cmd`, or `mvn`; overridable).
4. Applies every proven patch as a text edit on the exact line. Formatting and comments survive, so the diff is one
   line per patch.
5. Runs the tests again, comparing Surefire results with the baseline. If anything fails, it retries patch by patch to
   isolate the culprit, and drops and reports it.
6. Makes one commit per patch, re-runs doctor for before/after scores, removes the worktree and keeps the branch.

**What the agent gets back:** the branch name, the commits, the CVEs cleared, the before/after score and any patch
that failed tests and why. The agent then reviews the diff and explains it to the developer.

**Why it's needed.** It's a mechanical change that has one correct answer and a mechanical check, so having an agent
hand-edit XML adds risk without adding judgement. A separate branch plus a test gate is the only way a team will trust
automated POM changes.

### 4.3 `verify_cleanup`: proof for the agent's own work

**What it returns** (for the working tree or a branch): it compiles; tests pass compared with the baseline; which
targeted findings are **resolved / still present**; **new findings introduced** in files the agent touched; the score
change.

**Why it's needed.** It's the feedback loop agents don't have. Without it the agent stops when it *thinks* it's
finished. With it, the agent keeps going until stealth says green, and the developer gets a verifiable summary instead
of a claim.

### 4.4 `upgrade_impact`: what this upgrade breaks, line by line

**Input:** `groupId`, `artifactId`, `toVersion`. **Output:** a worklist of call sites, each with the `file:line`, the
API used, what changed (removed / signature changed / moved package / deprecated), and the replacement where known.

**How:**

- **The API diff.** Resolve the old and new jars with Maven Resolver (which we already use), then diff their public API
  with **japicmp** (Apache-2.0).
- **The repo's references.** Find what this repo actually calls with **ASM** (BSD) over compiled classes: precise owner,
  name and descriptor, mapped back to source lines through the class files' line-number tables. If the repo can't be
  built, fall back to import-level matching from sources, and say so.
- **The intersection.** Intersect the two, add a small curated table of known moves (`javax.*` → `jakarta.*`, Spring
  Security's removed adapters), and link the official migration guide.

**Why it's needed.** "Migrate to Spring Boot 3" is a vague, frightening task. "41 call sites, 38 of them a mechanical
package rename, 3 that need a security config rewrite, here they are" is one an agent can finish and a team can
estimate. It's also exactly the input OpenRewrite recipes need later: recipes automate the mechanical part, and the
agent handles the rest.

### 4.5 `test_gaps` and `verify_tests`: make "tests pass" mean something

**The problem.** Legacy repos often have thin tests, and usually in precisely the code an upgrade touches. A green
build after an upgrade then proves very little.

**`test_gaps`** takes the call sites from `upgrade_impact` (or every use of a library) and reports which ones **no test
executes**. For each gap it gives:

- the public method enclosing the call site, which is what to test;
- the existing test class to extend, if any;
- the repo's test style (JUnit 4 or 5, Mockito, Spring slices such as `@WebMvcTest`/`@DataJpaTest`);
- what to pin: *current* behaviour (characterization tests), not new behaviour.

**`verify_tests`** runs the suite on the current code with JaCoCo and reports whether the agent's new tests pass and
whether the gap lines are now actually executed. JaCoCo is invoked from the command line in the temporary worktree,
so the user's POM isn't modified.

**The payoff.** The upgrade branch must then pass those same tests, and the report can say *"behaviour pinned at 41 of
41 affected call sites; all pass after the upgrade"*. That's a defensible basis for merging a migration, and the agent
did the writing.

**Why stealth and not just "agent, write some tests"?** Without stealth the agent doesn't know which lines matter for
this upgrade, can't measure whether its tests reach them, and tends to write tests of new behaviour rather than
pinning the old. stealth supplies the targets and the measurement; the agent supplies the tests.

## 5. The hooks: prevent new debt while the agent works

### 5.1 What a hook is

Claude Code hooks are shell commands Claude Code runs automatically at fixed moments in a session. They're configured
in `.claude/settings.json`, which can be committed, so every developer's agent on the repo gets them. The command
receives a JSON description of what just happened on stdin. It can respond with a decision to block and a reason,
which Claude reads and must act on before continuing.

The difference from the MCP tools matters:

- **MCP:** the agent *decides* to ask stealth.
- **Hook:** stealth checks automatically, every time, whether anyone thought to ask.

Prevention only works if it's automatic, so we expect the hooks to be the most-used part of stealth.

### 5.2 The two hooks

`stealth hooks install` writes both (project settings by default; `--user` for the user's own settings):

**After an edit (PostToolUse on `Edit|Write|MultiEdit`) → `stealth hook post-edit`**

- **`pom.xml` changed:** diff its dependencies against HEAD. For each one added or changed, run the same check as the
  `check_dependency` tool: known vulnerabilities, unmaintained, or not on Maven Central at all (an artifact the agent
  invented). On a problem, block with a fix:
  > `commons-text 1.9` has CVE-2022-42889 (critical, remote code execution); use 1.15.0.
- **Java or properties file changed:** secrets scan of just that file (offline, milliseconds). Block on a newly added
  secret, with the line and the fix: *read it from an environment variable or the secret store; don't commit it.*

**When the agent thinks it's finished (Stop) → `stealth hook stop`**

- Runs a fast check on what *this session* changed: deps if a POM changed, secrets and duplication on changed files.
  It compares finding fingerprints with the session's baseline. If the session **introduced** findings, it blocks with
  the list, e.g. *"you added a hardcoded API key at `application.properties:12` and a copy of `DateUtils.parse` at
  `OrderService.java:40`"*. The agent fixes them before handing back.
- Pre-existing debt is never reported here. That's `doctor`'s and `clean`'s job, and nagging about old debt on every
  stop would get the hook uninstalled.

### 5.3 Rules the hooks must follow

- **Fast:** under ~2 seconds. The hook calls the already-running `stealth mcp` server over HTTP (warm JVM, warm
  caches, token from `~/.stealth/mcp-token`), and falls back to an in-process check that uses only cached data.
- **Fail open:** if stealth can't run or can't reach the network, allow and print a warning. Never block the agent
  because of stealth's own failure.
- **No loops:** respect the Stop event's `stop_hook_active` flag and block at most once per stop.
- **Quiet:** report only what the session introduced, with the fix in the message.

(Field names and decision formats are to be confirmed against the current Claude Code hooks documentation when we
build this.)

### 5.4 Why Java teams need this

The commonest ways agents add debt to Java repos are adding a vulnerable or invented artifact, pinning a stale version
from training data, and hardcoding credentials. Today nothing catches them until CI or a security review days later,
by which time the developer has to untangle the agent's work. The hook catches them seconds after the edit, and the
feedback goes **to the agent**, which fixes them before the developer ever sees them. It also gives teams a reason to
commit stealth into the repo's settings: every agent on the team gets the guardrail automatically.

## 6. How the pieces fit together

```
            prevent new debt                              pay down existing debt
  ┌───────────────────────────────┐      ┌──────────────────────────────────────────────────┐
  │ hooks (automatic)             │      │ doctor / repo_health   → what's wrong            │
  │  post-edit: deps, secrets     │      │ plan_cleanup           → what we can fix now     │
  │  stop: what this session added│      │ apply_cleanup          → proven patches, branch  │
  └───────────────┬───────────────┘      │ upgrade_impact         → migration worklist      │
                  │                      │ test_gaps/verify_tests → safety net, measured    │
                  │                      │ verify_cleanup         → proof                   │
                  │                      └────────────────────────┬─────────────────────────┘
                  └──────────── same engine: analyzers, resolver, OSV, fingerprints, cache ───┘
```

**Example session in Claude Code on a Boot 2.7 service** (the numbers are illustrative until step 1 of the delivery
order measures them):

1. *"How's this repo looking?"* → `repo_health`: security D, 108 findings, mostly through Spring Boot 2.7.
2. *"Clean up what we can without the Boot upgrade."* → `plan_cleanup`: 9 proven patches clearing roughly 80 CVEs, a
   sized Boot 3 migration, and one hardcoded secret. The agent shows the plan, and the developer says go.
3. `apply_cleanup` → branch `stealth/clean-20261006-1412`, 9 commits, tests green, security score rises. The agent
   walks the developer through the diff.
4. The agent moves the hardcoded key into an environment variable (the developer rotates it), then `verify_cleanup`
   reports the secret finding resolved.
5. Later: *"Plan the Boot 3 migration."* → `upgrade_impact` (41 call sites) → `test_gaps` (7 untested) → the agent
   writes characterization tests → `verify_tests` (all 41 covered) → the migration on a branch → `verify_cleanup`.
6. Throughout, the hooks catch anything new the agent adds before it finishes.

## 7. Why we're the ones to build this

| Tool | Does well | Doesn't do |
|---|---|---|
| Dependabot / Renovate | Bump declared versions, open PRs | Look at the resolved tree, override Boot-managed properties, tell you what breaks, or talk to agents |
| Snyk and similar scanners | Find CVEs; some suggest upgrade paths or pins | Prove fixes against your tests on a branch, size migrations at call-site level, or work with agents in their loop |
| OpenRewrite / Moderne | Automated migrations through recipes | Choose the cheapest fix for a CVE, measure test gaps, or guard an agent's edits as they happen. (We plan to *use* OpenRewrite recipes, after the licence audit) |
| The agent alone | Writing code, judgement | Seeing the resolved tree, knowing CVE data, measuring coverage, or proving its own work |

**What's novel:** agent-native, Java-specific, deterministic proof. We already have most of the hard parts:

- an effective-model loader that traces versions through properties, BOMs and parents;
- OSV integration;
- stable finding fingerprints;
- a warm local server with per-repo caching.

`clean` and the hooks are mostly new uses of that engine, which is why this is the right next step rather than a new
product.

## 8. Risks and how we contain them

| Risk | Mitigation |
|---|---|
| Version overrides can break at runtime even when they compile (e.g. snakeyaml 2.x under Boot 2.7) | Same-major by default; cross-major marked risky and never applied automatically; test gate; patches isolated one per commit so a bad one is easy to drop |
| Weak test suites make the test gate weak | `test_gaps` measures exactly that and gets the agent to fill it before risky changes; reports always say how well the affected code is covered |
| Building the repo for `upgrade_impact` / JaCoCo is slow or fails (private repositories, `settings.xml`, odd builds) | Use the user's own `mvnw` and settings; time limits; import-level fallback that says it's approximate |
| Hooks feel slow or noisy and get uninstalled | Fast checks against the warm server; only what the session introduced; fail open; one block per stop |
| Competitors add similar features | Our lead is being agent-native plus doctor's engine; ship patch planning and hooks first, because they're most visible |
| OpenRewrite recipe licensing (some recipe modules are under Moderne's source-available licence) | Licence audit (already on the ROADMAP) before using recipes; `upgrade_impact` and `test_gaps` don't depend on OpenRewrite |

## 9. Delivery order

Each step is a separate PR and useful on its own.

1. **Proven patches:** `plan_cleanup`, `apply_cleanup`, `verify_cleanup` (MCP and CLI). Also measure the headline
   number: *share of CVEs cleared without a migration, tests green* on `boot2-legacy` and 3–4 public Spring Boot 2.7
   repos. If it's high, that's the launch story. If it's low, we learn that before building more.
   **Status: built** (see [the ticket](../tickets/clean/ticket-proven-patches.md)). On `boot2-legacy`: 47 of 108
   cleared by the safe patches, 76 with the reviewed minor jumps, tests green, still on Spring Boot 2.7. Public repos
   not measured yet.
2. **Hooks:** `stealth hooks install`, `post-edit` (dependencies and secrets), `stop` (what the session introduced).
3. **`upgrade_impact`:** japicmp plus ASM call-site analysis.
4. **`test_gaps` and `verify_tests`:** coverage of affected call sites, measured with JaCoCo.
5. **Later:** OpenRewrite recipes for the mechanical parts of migrations; `--open-pr`.

This replaces the current Phase 2 plan's separate `stealth upgrade` command. Phase 2's git safety rules, test
verification harness and vulnerability-driven bumps all become parts of `clean`, and the Phase 3 hook item moves
earlier.

## 10. Questions for review

1. **Positioning:** is "Java teams with agents, older Spring Boot under CVE pressure" the right first market? Is there
   a sharper one?
2. **Write boundary:** is "stealth writes only proven patches, only on a new branch" the right line? Should
   `apply_cleanup` ever be allowed to apply to the working tree directly (e.g. `--in-place` for CI)?
3. **Order:** patches before hooks? Hooks are smaller to build and arguably stickier.
4. **Default risk level:** same-major only by default? How should we present cross-major overrides that *do* pass
   tests?
5. **Building user repos:** `upgrade_impact` and `verify_tests` need to compile and run tests. Is relying on the repo's
   own `mvnw` and settings acceptable, or do we need a sandbox?
6. **Hook strictness:** should a newly added secret or critical CVE always block, or should teams be able to set the
   level in `.stealth.yml`?
7. **Naming:** `clean` vs `fix` vs `upgrade` for the command; `plan_cleanup` vs `plan_fixes` for the tools.
