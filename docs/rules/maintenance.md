# `maintenance`: dependencies with no recent release

Reports direct dependencies whose newest release is more than 2 years old. They may be on their latest version, so
[`deps`](deps.md) can't see them, but nobody is shipping fixes: when a vulnerability is found there may never be a
fixed version to upgrade to. Category: **tech**.

- Only direct dependencies are checked, since those are the ones you chose and can replace.
- Versions managed by an external parent or BOM (such as `spring-boot-starter-parent`) are skipped: whoever maintains
  the BOM keeps them current.
- A few finished-not-abandoned artifacts are skipped (`javax.inject:javax.inject`, `aopalliance:aopalliance`,
  `com.google.code.findbugs:jsr305`). Accept others in `.stealth.yml` with an `allow.dependencies` entry.
- Release dates come from Maven Central's search API. The repository's own file dates aren't used: storage migrations
  re-touch old files, so commons-collections 3.2.2 (released 2015) looks like it was published in 2025.
- The search index lags Maven Central by days to weeks. A newest release that isn't indexed yet is recent, so it's
  treated as maintained.
- If Maven Central or its search API can't be reached and nothing is cached, the analyzer fails rather than reporting
  everything as maintained.

## maintenance-no-recent-release

`maintenance/no-recent-release`, default severity **low**. Dependencies in `test` scope are marked `[test scope]`.

The threshold is 2 years. Change it per repository in `.stealth.yml`:

```yaml
analyzers:
  maintenance:
    stale-after: P3Y
```

`-Dstealth.maintenance.stale-after=P3Y` changes the default for every repo; `.stealth.yml` wins over it.
