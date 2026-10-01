# ADR-0002: Scoring formula

- **Status:** Accepted
- **Date:** 2026-09-29
- **Ticket:** CU-869f96kzk

## Context

`stealth doctor` gives a repo a 0–100 health score for tech debt, security debt and overall, plus a ranked "fix these
first" list. Once teams use `--fail-under` in CI, the score is a public contract: if a stealth upgrade drops a repo from
72 to 64 with no code change, their build breaks.

The formula has to be:

- **Explainable.** A user asking "why 64?" should get a list of deductions that add up.
- **Hard to game or swamp.** One noisy analyzer (200 slightly outdated dependencies) must not drive the score to zero
  and hide everything else.
- **Sensitive to what matters.** A single critical vulnerability should never leave a repo looking healthy.
- **Deterministic.** Same findings → same score, on any machine.

## Decision

### Weights

| Severity | Weight |
|---|---|
| Critical | 15 |
| High | 8 |
| Medium | 3 |
| Low | 1 |
| Info | 0 |

### Category score

For each category (tech, security), findings are grouped by analyzer. Within each analyzer, findings are sorted by
weight, highest first, and the *i*-th finding (1-based) deducts `weight / √i`. Each analyzer's total deduction in a
category is capped at **40**.

```
deduction(analyzer) = min(40, Σᵢ weightᵢ / √i)
categoryScore       = max(0, 100 − Σ deduction(analyzer))
```

The square-root decay means the first findings hurt most and each extra one of the same kind hurts less. The cap means
no single analyzer can take a category below 60 on its own. Some reference points for one analyzer:

| Findings | Deduction |
|---|---|
| 1 medium | 3.0 |
| 10 medium | 15.1 |
| 50 medium | 38.3 |
| 200 medium | 40 (capped) |
| 1 critical | 15.0 |
| 3 critical | 34.3 |

### Overall score

```
overall = 0.6 × security + 0.4 × tech
```

Security is weighted higher because it's the more urgent kind of debt.

### Critical ceiling

If any **critical** finding is present, its category score **and** the overall score are capped at **50**. A repo with
a critical vulnerability can't pass a reasonable `--fail-under`, however clean the rest is.

### Rounding

All arithmetic is in `double`; category and overall scores are each rounded half-up to an integer only at the end
(overall is computed from the unrounded category scores). The JSON output also carries each analyzer's deduction to one
decimal place so the terminal can show the breakdown.

### What counts

Only findings that survive `.stealth.yml` (ignored paths, rules set to `off`, allowlists) are scored, using their
overridden severity. Info findings are listed but weigh 0.

A run that leaves analyzers out (`stealth doctor --hygiene`, `--security`, …) doesn't produce the repository's score:
a category where only some analyzers ran is shown as **partial**, naming the ones that did, and there's no overall
score unless both categories ran in full. Only a category whose analyzers all ran (or failed, which marks it
*incomplete*) counts.

### Worked example

| Analyzer | Category | Findings | Deduction |
|---|---|---|---|
| Dependency freshness | tech | 12 medium, 6 low | 18.4 |
| Duplication | tech | 4 medium | 8.4 |
| Repo hygiene | tech | 2 low | 1.7 |
| Vulnerabilities | security | 2 high, 1 medium | 15.4 |
| Secrets | security | 1 high | 8.0 |

Tech = 100 − 28.4 = **72**. Security = 100 − 23.4 = **77**. Overall = 0.6 × 76.6 + 0.4 × 71.6 = **75**.

### Grades

For display only: A ≥ 90, B ≥ 75, C ≥ 60, D ≥ 40, F < 40. CI gates use the number.

### "Fix these first" list

1. Findings that share one fix are grouped into one item: same `component` and `fixedVersion` (one bump fixes four
   CVEs), otherwise one item per finding.
2. Items are ordered by:
   1. highest severity in the item
   2. points the overall score would gain if the item were fixed (recomputed, so capped analyzers rank lower). The
      gain is computed **without the critical ceiling**: while any critical finding remains the overall score is
      pinned at 50, so with the ceiling every other fix would gain 0 and the order would fall to the tie-breakers
   3. has a concrete remediation (`fixedVersion`) before one without
   4. items with a security finding before tech-only items
   5. `ruleId`, then path, then fingerprint, so ties are always broken the same way
3. The terminal shows the top 10; JSON and MCP return the full list.
4. Findings that aren't about a dependency (no `component`, e.g. a missing CODEOWNERS file or a secret) are one item
   each, even at the same location: they're separate changes. Dependency findings at the same `pom.xml` line are one
   item, which also groups transitive vulnerabilities under the direct dependency or parent that brings them in.

### Stability guarantees

- The JSON output carries `"scoringVersion": 1`. Any change to weights, decay, caps, ceilings or category weights bumps
  it and is listed in the release notes.
- Scoring changes only ship in **minor** releases, never in patch releases.
- **New rules ship unscored.** A new rule reports findings but doesn't affect the score for one minor release (marked
  "preview" in output), so upgrading stealth doesn't break CI gates without warning. Users can opt in early.
- Before 1.0 we may change `scoringVersion` more often, but the rules above (versioned, announced, minor-only) still
  apply.

## Consequences

- The scoring engine is a pure function of `(findings, analyzer → rules, scoringVersion)`, easy to unit test with
  fixed inputs. The worked example above should be one of those tests.
- Scores are only comparable between repos with similar `.stealth.yml` files, since overrides change what's scored.
  The platform (Phase 5) should show when a score is config-adjusted.
- The per-analyzer cap means a repo can score 60+ in a category while having hundreds of findings. That's deliberate:
  the list and counts show the volume; the score shows breadth and severity.

## Alternatives considered

- **Plain sum of weights:** easy to explain, but a large legacy repo scores 0 immediately and stays there, so the
  score stops being useful as a trend.
- **Findings per thousand lines of code:** normalises for size but rewards large codebases, and a single committed
  secret is equally bad in any size repo.
- **Exponential decay (`100 × e^(−D/k)`):** smooth and never negative, but deductions no longer add up, which makes
  "why 64?" much harder to answer.
- **Overall = minimum of categories:** simple and strict, but hides improvements in the better category.

## Expected to change

- The weights, the 40 cap, the 50 critical ceiling and the 0.6/0.4 split are first guesses. Tune them against
  spring-petclinic and the fixture repos before the Phase 1 launch, then freeze them as `scoringVersion: 1`.
- Grade boundaries.
- Whether "unmaintained dependency" findings belong to security rather than tech.
- How generous tech scores are. At the reference date `boot2-legacy`, a deliberately neglected repo, scores
  **tech 91 (A)**: 4 outdated dependencies (−6.2), missing CODEOWNERS and CI (−1.7) and one unmaintained dependency
  (−1.0). Its security score is 50 (critical ceiling; vulnerabilities alone hit the 40 cap). Outdated dependencies and
  hygiene may need more weight before the launch freeze.
