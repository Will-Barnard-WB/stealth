# Architecture Decision Records

Short records of decisions that are hard to change later, usually because they become a public contract (the JSON and
SARIF output, the score users gate CI on, the config file format).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-finding-model.md) | Finding model | Accepted |
| [0002](0002-scoring-formula.md) | Scoring formula | Accepted |
| [0003](0003-sarif-mapping.md) | SARIF mapping | Accepted |
| [0004](0004-stealth-yml-format.md) | `.stealth.yml` format | Accepted |

## Process

1. Copy [0000-template.md](0000-template.md) to `NNNN-short-title.md` with the next number.
2. Open a PR with status **Proposed**. The PR is the review.
3. Set the status to **Accepted** in the PR before merging.
4. To reverse a decision, write a new ADR and mark the old one **Superseded by** it. Small corrections to an accepted
   ADR (anything listed under "Expected to change") can be edited in place.
