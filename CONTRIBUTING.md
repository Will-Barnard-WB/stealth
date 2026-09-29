# Contributing

Thanks for your interest! stealth is at a very early stage, so the most useful contributions right now are:
- Feedback on the [roadmap](ROADMAP.md), especially from anyone maintaining large Java/Spring codebases
- Real-world examples of tech or security debt that `stealth doctor` should catch
- Code, once the Phase 0 scaffold lands

## Before you start
- For anything beyond a small fix, open an issue first so we can agree on the approach.
- Security issues go through [SECURITY.md](SECURITY.md), not public issues.

## Development (once the scaffold exists)
- Java 21 and the Maven wrapper (`./mvnw`)
- `./mvnw verify` runs formatting checks and all tests
- New analyzers need tests against the repos in `fixtures/`, including tests showing they *don't* flag clean code

## Pull requests
- Keep PRs focused on one change.
- Describe what changed and why, and how you tested it.
- By contributing, you agree your contribution is licensed under the [Apache License 2.0](LICENSE).
