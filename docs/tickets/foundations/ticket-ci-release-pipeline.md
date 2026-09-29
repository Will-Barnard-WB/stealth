# CI and release pipeline (GitHub Actions + JReleaser)

ClickUp: [CU-869f96kyh](https://app.clickup.com/t/869f96kyh)

### What is the problem we are trying to solve?

Nothing checks PRs today, and there's no way to ship a build. We need every PR verified automatically, and a tag on `main` to publish the CLI to GitHub Releases and a Homebrew tap without manual steps.

### Why it's important or urgent?

Needed before Phase 1 work gets merged in parallel: without CI, broken builds and unformatted code land on `main`. The release pipeline must work before the Phase 1 launch, and doing it early surfaces signing/packaging problems while the stakes are low.

### What is the proposed solution?

- Every PR and push to `main` runs build, tests and format check, and shows the result on the PR
- Pushing a `vX.Y.Z` tag builds and publishes a GitHub Release with the CLI artifacts and updates the Homebrew formula

### Technical approach

- **`ci.yml`**: on `pull_request` and `push` to `main`
  - `actions/setup-java` (Temurin 21) with Maven cache
  - `./mvnw -B verify` (tests + Failsafe ITs + `spotless:check`)
  - Matrix `ubuntu-latest` + `windows-latest` (the team develops on Windows; path handling bugs should show up in CI)
  - Upload surefire/failsafe reports as artifacts on failure
- **`release.yml`**: on tag `v*`
  - `./mvnw -B verify` then JReleaser (`jreleaser-maven-plugin`)
  - Assemble the CLI as a zip (jar + launch scripts), then GitHub Release with changelog from `CHANGELOG.md` (works with the `/release` skill)
  - Homebrew tap repo `<org>/homebrew-tap`, updated by JReleaser with a scoped token stored as a repo secret
- **Branch protection on `main`**: require `ci` to pass before merge
- **Dependabot** for Maven and GitHub Actions versions (weekly)
- **Risk**: the Homebrew tap needs a PAT or GitHub App token with write access to the tap repo. Keep it scoped to that repo only

### Definition of done

- [ ] PRs show a required `ci` check on Ubuntu and Windows
- [ ] A failing test or format violation fails the check
- [ ] A test tag (`v0.0.1-rc.1`) produces a GitHub Release with the CLI zip attached
- [ ] `brew install <org>/tap/<name>` installs and `<name> --version` runs
- [ ] Branch protection enabled on `main`
- [ ] Dependabot config committed
- [ ] Release steps documented in CONTRIBUTING (or the `/release` skill verified end to end)

### Out of scope

- Maven Central publishing: not needed until the libraries are consumed by others
- GitHub Action for `stealth doctor` in users' repos: Phase 4
- Native images / SDKMAN / Scoop: later distribution channels

Suggested branch: `feature/CU-869f96kyh_ci_release_pipeline`
Depends on: Maven scaffold, name availability (for tap/org names)
