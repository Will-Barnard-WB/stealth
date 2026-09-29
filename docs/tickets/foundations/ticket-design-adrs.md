# Design ADRs: Finding model, scoring, SARIF mapping, .stealth.yml

ClickUp: [CU-869f96kzk](https://app.clickup.com/t/869f96kzk)

### What is the problem we are trying to solve?

Phase 1 analyzers, renderers, the scoring engine and the MCP server all share four contracts: what a finding looks like, how findings become a score, how they map to SARIF, and what users can configure. If these are decided ad hoc inside the first analyzer PR, every later analyzer and output format inherits accidental choices, and changing them after launch breaks users' CI gates and SARIF history.

### Why it's important or urgent?

It blocks the analyzer SPI, scoring engine, renderers and `.stealth.yml` tickets in Phase 1. The score and `--fail-under` become a public contract once teams gate CI on them.

### What is the proposed solution?

Four short Architecture Decision Records in `docs/adr/`, each reviewed and accepted before the matching Phase 1 work starts.

### Technical approach

One ADR per decision (context / decision / consequences / alternatives considered):

1. **ADR-001 Finding model**: `Finding` record fields: stable `ruleId`, category (tech/security), severity, message, location (file, line, module), optional `fixedVersion`/remediation, and a stable fingerprint for de-duplication and baselines across runs
2. **ADR-002 Scoring formula**: 0–100 per category and overall; severity weights; diminishing returns so one noisy analyzer can't zero the score; how the ranked "fix these first" list is ordered; stability guarantees across versions
3. **ADR-003 SARIF mapping**: SARIF 2.1.0: `ruleId` → `rules[]`, severity → `level`/`security-severity`, locations for non-file findings (e.g. dependency in `pom.xml`), fingerprints → `partialFingerprints` so GitHub code scanning tracks findings across runs
4. **ADR-004 `.stealth.yml` format**: ignored paths, severity overrides, allowlists (secrets, dependencies), `fail-under`; versioned schema (`version: 1`); unknown keys warn rather than fail

- Template in `docs/adr/0000-template.md`
- **Risk**: over-designing before real analyzers exist. Keep each ADR short and mark what's expected to change

### Definition of done

- [ ] `docs/adr/` with template and ADRs 001–004
- [ ] Each ADR reviewed and marked **Accepted**
- [ ] SARIF sample output validated against the SARIF 2.1.0 schema
- [ ] Example `.stealth.yml` included in ADR-004
- [ ] Phase 1 tickets (analyzer SPI, scoring engine, renderers, `.stealth.yml`) link to the relevant ADR

### Out of scope

- Implementing any of it: Phase 1 tickets
- Platform/API data model: Phase 5

Suggested branch: `chore/CU-869f96kzk_design_adrs`
Can run in parallel with the scaffold and fixtures.
