# stealth clean, step 3: upgrade impact

ClickUp: _not yet created_ · Proposal: [docs/direction/clean-and-hooks.md](../../direction/clean-and-hooks.md) (delivery step 3)

### What is the problem we are trying to solve?

`plan_cleanup` leaves what needs a major upgrade, and "upgrade to Spring 6" is vague and frightening: nobody knows how
much of the code it touches until they try, and agents find out one compile error at a time.

### What is the proposed solution?

`stealth clean --impact group:artifact:version` (MCP `upgrade_impact`): every place this repository uses an API the
upgrade removes or deprecates, with `file:line`, what changed, and what to use instead where it can tell.

### Technical approach (core `dev.stealth.core.impact`)

- **Versions:** the current one from the repository's resolved dependency tree; both jars via
  `MavenModelLoader.resolve` (Maven Resolver, the same local repository and remote).
- **`ApiSurface` / `ApiChanges`:** public classes and public/protected members of each jar, read with ASM (no class
  loading), diffed: classes and members removed (a changed signature is the old one removed), newly deprecated.
- **`UsageScanner`:** ASM over each module's `target/classes` and `target/test-classes`: method and field
  references, `new`, casts, class literals, method handles, supertypes, signature types, and **overrides** (a declared
  method checked against its class's direct supertypes, which is how framework upgrades usually break), each with its
  source line and file.
- **Fallback:** without compiled classes, imports of removed or deprecated classes in the sources, marked approximate.
- **Hints:** known moves (`javax.*` → `jakarta.*`, `WebSecurityConfigurerAdapter`, `WebMvcConfigurerAdapter`); a class
  with the same simple name elsewhere in the new jar ("moved to …?"); a method with the same name and another
  signature ("now …", with fully qualified types when the short names match).

### Definition of done

- [x] `UpgradeImpactTest`, offline on every OS: two library versions compiled in the test and installed in a local
  Maven repository; an app compiled against the first. Finds the removed method, changed signature, removed and moved
  classes, a deprecated method and a broken override, at the right lines, removed first; the import fallback; a
  dependency the repo doesn't use; an unpublished version.
- [x] CLI `--impact`, MCP `upgrade_impact` (listed and wired in `McpServerIT`).
- [x] Real run on a compiled copy of `boot2-legacy`: spring-web 5.3.31 → 6.1.14 removes 520 APIs; the one used is the
  `doFilterInternal` override in `RequestIdFilter.java:20`, hinted to `jakarta.servlet` types. Guava 30 → 33: nothing
  used. About 3 s.
- [x] `./mvnw verify` passes.

### Notes from implementation

- **ASM instead of japicmp:** the proposal planned japicmp for the API diff. ASM, already on the classpath and
  needed for the usage side anyway, covers what matters here (removed, changed, deprecated) with one BSD dependency.
- **Overrides** weren't in the first version and the real run showed why they matter: a call-only scan reported
  nothing for spring-web 6, because the break is a method the app *declares*.

### Not done

- **A whole framework at once:** `--impact` takes one artifact. Sizing "Spring Boot 2.7 → 3.x" means running it for
  each artifact whose managed version changes; expanding a parent or BOM automatically is a follow-up.
- **Compiling for the user:** when the project isn't compiled, it falls back to imports rather than building it.
