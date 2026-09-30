# `deps`: dependency freshness

Reports dependencies that have a newer stable release on Maven Central. Category: **tech**.

Each version is reported once, where it's set. A version managed by an external parent (such as
`spring-boot-starter-parent`) or an imported BOM is reported on that parent or BOM, since that's the one version to
change, and the finding says how many of the module's dependencies it manages. A version set once in a parent POM and
used by several modules is one finding, at the parent.

What doesn't count as a newer version:

- **Pre-releases** (`-M1`, `-RC2`, `.CR1`, `-alpha`, `-beta`, `-SNAPSHOT`, `-preview`, `-ea`), unless the version in use
  is a pre-release itself.
- **Other variants**: `33.7.2-android` isn't an upgrade from `30.1-jre`.
- **Date-stamped relics** such as `commons-collections:20040616`, which sort above `3.2.2` but are older.

Dependencies that aren't on Maven Central (a company's internal libraries) are skipped. If Maven Central can't be
reached and nothing is cached, the analyzer fails rather than reporting the rest as up to date. `stealth doctor
--offline` uses only cached lookups (kept in `~/.stealth/cache/http`, refreshed after 24 hours).

## deps-outdated-major

`deps/outdated-major`, default severity **medium**. A new major version is available, e.g. `2.7.18` → `4.1.1`. Major
upgrades usually need code changes, and the gap gets harder to close the longer it's left.

## deps-outdated-minor

`deps/outdated-minor`, default severity **low**. A new minor version is available, e.g. `3.9` → `3.21.0`.

## deps-outdated-patch

`deps/outdated-patch`, default severity **info** (listed, but doesn't affect the score). Only the patch number differs,
e.g. `2.0.19` → `2.0.20`.

To change a severity or turn a rule off, use `.stealth.yml` (see [ADR-0004](../adr/0004-stealth-yml-format.md)).
