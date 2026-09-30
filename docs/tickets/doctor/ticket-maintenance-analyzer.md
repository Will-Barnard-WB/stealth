# Maintenance analyzer (abandoned dependencies)

ClickUp: _not yet created_

### What is the problem we are trying to solve?

A dependency can be on its latest version and still be a liability because nobody maintains it anymore. It won't get security fixes or support for new Java versions, and the freshness analyzer can't see this. We should flag dependencies whose latest release is more than 2 years old.

### Who is impacted?

CLI and MCP users, especially teams with long-lived services that picked up niche libraries years ago.

### Why it's important or urgent?

Abandoned dependencies are future security debt: when a CVE lands there's no fixed version to upgrade to. It's a cheap, high-signal finding that version-bump tools don't surface.

### What is the proposed solution?

A finding per dependency with no release in over 2 years (configurable), showing the date of the last release.

### Technical approach

- **`core`**: `MaintenanceAnalyzer` (category tech), reusing `MavenCentralClient` and the shared HTTP cache
- Last release date: Central search API `core=gav` `timestamp` for the latest version. Fall back to the `Last-Modified` header of the latest version's `.pom` if search is unavailable
- Threshold: `maintenance.staleAfter` in `.stealth.yml`, default `P2Y`
- Skip artifacts managed by a maintained BOM (e.g. Spring Boot–managed artifacts) to avoid noise. **Proposed**, confirm with precision results
- Severity low (info for test scope). Relocated artifacts (`<relocation>` in the POM) are reported as "moved to `x:y`" instead
- **Risk**: stable, finished libraries show up as false positives. Keep an in-repo allowlist of known-stable artifacts and let `.stealth.yml` extend it

### Definition of done

- [x] WireMock tests for release-date lookup (`MavenCentralSearchTest`); the `Last-Modified` fallback was dropped, see below
- [x] Precision tests: a stale dependency in `fixtures/boot2-legacy` is flagged, and `fixtures/boot4-clean` isn't
- [x] Threshold configurable and tested
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **No `Last-Modified` fallback.** Maven Central's file dates are re-touched by storage migrations: commons-collections 3.2.2 (released 2015-11-12) reports `Last-Modified: 2025-10-11`. The fallback could only make stale artifacts look fresh, silently. Release dates come from the search API only; if it fails, the analyzer fails.
- **Search index lag:** releases from the last few weeks aren't indexed (commons-lang3 3.21.0, guava 33.7.2-jre, slf4j 2.0.20 at the reference date). A newest release missing from the index is treated as recent, so maintained.
- **Rule:** `maintenance/no-recent-release`, low. Test-scoped dependencies are marked `[test scope]` rather than downgraded to info, because ADR-0001 gives each rule one default severity.
- **Threshold:** `stealth.maintenance.stale-after` (ISO-8601 period, default `P2Y`) until the `.stealth.yml` ticket adds `maintenance.staleAfter`.
- **Parent/BOM-managed versions are skipped**, as proposed: boot2-legacy's precision results only flag commons-collections.
- **Relocation** ("moved to `x:y`") isn't implemented yet: none of the fixtures need it. Follow-up.
- Recorded search responses: `core/src/test/resources/__files/central-search/`.

### Out of scope

- GitHub activity signals (commits, issues, archived repos): later, needs the GitHub API and rate-limit handling

Suggested branch: `feature/CU-<clickup-id>_maintenance_analyzer`
Depends on: dependency freshness analyzer (`MavenCentralClient`, cache)
