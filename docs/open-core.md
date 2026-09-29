# Open-core boundary

stealth is open core. This page sets out which features are free and open source, and which will be part of the paid platform.

**The rule: anything that works on a single repo is free.** Anything fleet-wide, multi-repo, historical or team-level is paid.

| Free (Apache-2.0) | Paid platform |
|---|---|
| `doctor`: health score, findings, SARIF/JSON output | Fleet dashboard across all repos |
| `upgrade`: migrations and dependency bumps on one repo | Campaigns: one upgrade across hundreds of repos |
| `learn` / `check` and agent hooks | Combined tech + security backlog for teams |
| MCP server | Historical trends, debt in money terms |
| GitHub Action (CI gate, per-PR debt budget) | Teams, RBAC, SSO, audit |
| | Self-hosted enterprise distribution, support |

Nothing in the free tier requires an account, and nothing leaves your machine unless you choose to use
`stealth upload` or turn on opt-in usage stats.
