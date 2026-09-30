# `vuln`: known vulnerabilities

Reports dependency versions with a published security advisory on [OSV.dev](https://osv.dev), which combines the
GitHub Advisory Database, the NVD and others. Category: **security**.

Every dependency in each module's resolved tree is checked, not just the direct ones, using the versions Maven would
actually resolve (nearest wins). A finding for a transitive dependency points at the direct dependency that brings it
in, since that's the line a fix starts from, and names the path, e.g. `org.yaml:snakeyaml 1.30: … ; via
spring-boot-starter-web > spring-boot-starter`. The same issue under several ids (a GHSA and a CVE) is one finding;
the GHSA id is the primary one and the rest are listed as aliases.

Dependencies in `test` scope are reported too, marked `[test scope]`. They don't ship, but they run on developer
machines and in CI.

If OSV.dev can't be reached and nothing is cached, the analyzer fails rather than reporting "no vulnerabilities".
`stealth doctor --offline` uses only cached lookups (kept in `~/.stealth/cache/http`, refreshed after 24 hours).

## vuln-known-vulnerability

`vuln/known-vulnerability`. The severity comes from the advisory, not the rule (ADR-0001):

| CVSS v3 base score | Severity |
|---|---|
| 9.0 – 10.0 | critical |
| 7.0 – 8.9 | high |
| 4.0 – 6.9 | medium |
| 0.1 – 3.9 | low |

The score is calculated from the advisory's CVSS v3 vector. Advisories without one (some only publish CVSS v4) use
their own rating (GitHub's low / moderate / high / critical), and medium if they have none.

The fix is the lowest version that closes the affected range the version in use is in, in the same variant: an
advisory listing `32.0.0-android` is reported as `32.0.0-jre` for a project on `30.1-jre`. If the range is still open,
the finding says no fixed version is published.

To change a severity or turn the rule off, use `.stealth.yml` (see [ADR-0004](../adr/0004-stealth-yml-format.md)).
