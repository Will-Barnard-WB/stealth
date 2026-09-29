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

- [ ] WireMock tests for release-date lookup and the `Last-Modified` fallback
- [ ] Precision tests: a stale dependency in `fixtures/boot2-legacy` is flagged, and `fixtures/boot4-clean` isn't
- [ ] Threshold configurable and tested
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- GitHub activity signals (commits, issues, archived repos): later, needs the GitHub API and rate-limit handling

Suggested branch: `feature/CU-<clickup-id>_maintenance_analyzer`
Depends on: dependency freshness analyzer (`MavenCentralClient`, cache)
