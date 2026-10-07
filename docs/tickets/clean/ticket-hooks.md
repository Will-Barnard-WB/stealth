# stealth clean, step 2: Claude Code hooks

ClickUp: _not yet created_ · Proposal: [docs/direction/clean-and-hooks.md](../../direction/clean-and-hooks.md) (delivery step 2)

### What is the problem we are trying to solve?

The MCP tools only help when the agent asks. The commonest ways an agent adds debt to a Java repo (a vulnerable or
invented dependency, a version remembered from training data, a hardcoded credential) happen without anyone asking,
and are found days later in CI or a security review.

### What is the proposed solution?

`stealth hooks install` adds two Claude Code hooks to `.claude/settings.json`: after each edit, and before the agent
finishes. Each checks only what the agent introduced and, if it's a problem, blocks with a reason the agent acts on.

### Technical approach

- **Format, confirmed against Claude Code 2.1** with a probe hook: PostToolUse receives `cwd`, `tool_name` and
  `tool_input.file_path`; Stop receives `stop_hook_active`. Printing `{"decision":"block","reason":...}` makes the
  agent act on the reason (PostToolUse) or keep working (Stop).
- **`HookChecks`** (core `dev.stealth.core.hook`): compares an edited file with `git show HEAD:<path>`.
  - POMs: `PomDependencies` reads declared dependencies (with versions resolved from the POM's own properties). Each
    one added or changed is run through `DependencyCheck`; vulnerable or not on Maven Central blocks, with the version
    to use. Unmaintained is mentioned alongside.
  - Other files: `SecretsAnalyzer.scanText` on both versions; secrets not already committed block. `.stealth.yml`
    applies (`ignore`, allowlists, severity).
  - Stop: the same over every file `git status` reports as changed or new.
- **`stealth hook post-edit|stop`** (`HookMain`) runs without Spring (about 1 s, mostly JVM startup), always exits 0,
  and does nothing on a second Stop in the same turn. Errors print to stderr and let the agent carry on.
- **`stealth hooks install|uninstall`** (`HooksCommand`) merges into existing settings, replaces earlier stealth hooks
  instead of duplicating them, and removes only its own. `--user` for `~/.claude/settings.json`, `--command` for how
  to run stealth (e.g. `java -jar …` in development).

### Definition of done

- [x] `HookChecksTest`: vulnerable version through a property, invented artifact, unchanged POM checks nothing, new
  vs already-committed secret, ignored path, Stop over all changed files, nothing changed.
- [x] `HookMainTest`: block JSON for a new secret, nothing for a harmless edit, `stop_hook_active` respected, fails
  open outside git and on bad input.
- [x] `HooksCommandTest`: install keeps existing settings, re-install doesn't duplicate, uninstall removes only ours.
- [x] Verified with headless Claude Code in a copy of boot2-legacy:
  - Asked to add snakeyaml 1.30 "exactly that version", it was told about the CVEs after the edit, kept the version
    as asked but parsed with `SafeConstructor` (blocking CVE-2022-1471), and explained the rest at Stop.
  - Asked to hardcode a GitHub token, it used `GITHUB_TOKEN` instead. It did that on its own, so this run doesn't
    show whether the hook would have had to step in; the unit tests cover that path.
- [x] `./mvnw verify` passes.

### Notes from implementation

- **Speed:** skipping Spring for `stealth hook` keeps a check to about a second. Calling the warm `stealth mcp`
  server, as the proposal suggested, wasn't needed yet.
- **Symlinked paths:** the hook's `cwd` and git's top-level can differ (macOS `/tmp` → `/private/tmp`); paths are
  compared as real paths.
- **Not done:** duplication in the Stop check (it needs a whole-repo analysis, too slow for a hook); hooks for other
  agents (Cursor, Copilot) which use different formats.
