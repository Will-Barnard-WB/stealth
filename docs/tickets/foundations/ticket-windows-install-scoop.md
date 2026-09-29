# Windows install via Scoop

ClickUp: [CU-<clickup-id>](https://app.clickup.com/t/<clickup-id>)

### What is the problem we are trying to solve?

Homebrew is the only package-manager install for stealth (`brew install Will-Barnard-WB/tap/stealth`). On Windows, the only option today is downloading the release zip from GitHub, unpacking it, adding `bin/` to `PATH` by hand, and repeating that for every upgrade. Windows users need a one-command install and upgrade, the same as macOS and Linux users get with brew.

### Who is impacted?

- Windows developers using the CLI, including the core team (we develop on Windows, and CI already runs a `windows-latest` leg)
- Anyone following the README or the `/release` UAT checklist on Windows, since the only instructions there are for brew

### Why it's important or urgent?

- The Phase 1 launch targets Java/Spring teams, and many of them work on Windows. A "download the zip and edit PATH" step loses users before the first `stealth doctor` run.
- Release candidates ship often. Without a package manager, Windows testers upgrade by hand and end up running old builds.

### What is the proposed solution?

Publish stealth to a **Scoop** bucket as part of every release, next to the Homebrew tap. Windows users install and upgrade with:

```powershell
scoop bucket add stealth https://github.com/Will-Barnard-WB/scoop-bucket
scoop install stealth
scoop update stealth
```

Why Scoop:
- **It works like brew:** a Git repo of manifests (a "bucket", like a tap) that we own and update ourselves. No third-party review queue, so release candidates go out the moment they're tagged.
- **No admin rights:** it installs into the user profile and puts `stealth` on `PATH` through a shim. This matters on locked-down corporate laptops.
- **JReleaser supports it natively,** from the same ZIP we already build, so it adds no new build steps.

The other options, and why they come later:

| Option | Install command | Who publishes updates | Admin needed | Fit for now |
|---|---|---|---|---|
| **Scoop** (own bucket) | `scoop install stealth` | Us, automatically from CI | No | Best fit: brew-like, instant, JReleaser-native |
| **winget** | `winget install stealth` | PR to `microsoft/winget-pkgs`, reviewed by Microsoft | Depends on installer | After launch: preinstalled on Windows 11, but every version waits for review |
| **Chocolatey** | `choco install stealth` | Community repo moderation | Yes | Skip: moderation delay plus admin install |
| **MSI installer** (jpackage) | Double-click `.msi` | Us | Yes | Later: bundles a JRE (no Java needed) but needs WiX and a Windows release runner |

### Technical approach

- **`jreleaser.yml`**: add a `scoop` packager under `distributions.stealth`, pointing at a new `Will-Barnard-WB/scoop-bucket` repo. Set `active: ALWAYS` to match `brew` (switch both to `RELEASE` after launch). It uses the existing `javaArchive` ZIP, and JReleaser already generates `bin/stealth.bat` for Windows.
- **Java dependency:** the ZIP needs Java 21 on the machine. The brew formula declares `openjdk@21`. For Scoop, add `java/temurin21-jre` as a dependency in the manifest (`depends`, or `suggest` if we don't want to force the `java` bucket). If JReleaser's default Scoop template can't express this, use a custom template under `src/jreleaser/distributions/stealth/scoop/`.
- **`release.yml`**: add `JRELEASER_SCOOP_GITHUB_TOKEN: ${{ secrets.SCOOP_BUCKET_TOKEN }}`, a fine-grained token with `contents: write` on the bucket repo only (same pattern as `HOMEBREW_TAP_TOKEN`).
- **New repo** `Will-Barnard-WB/scoop-bucket`: empty `bucket/` folder and a README with install instructions.
- **Docs:** add a Windows install section to README, a note in CONTRIBUTING next to the tap line, and a Scoop line in the `/release` skill's UAT template.
- **Risks:**
  - Using JReleaser's Boot `JarLauncher` main class through `stealth.bat` on Windows hasn't been tested yet. Check it early on a real install.
  - Scoop checks `hash` in the manifest. JReleaser fills it in, but a re-run with `overwrite: true` that rebuilds the ZIP would change the hash. Make sure the manifest is regenerated on the same run.

### Definition of done

- [ ] `jreleaser.yml` has a `scoop` packager, and `./mvnw -N jreleaser:config` validates it
- [ ] `scoop-bucket` repo exists, and the `SCOOP_BUCKET_TOKEN` secret is set, scoped to that repo
- [ ] An rc tag (e.g. `v0.1.0-rc.N`) updates `bucket/stealth.json` automatically
- [ ] On a clean Windows machine with Scoop: `scoop bucket add` + `scoop install stealth` works, and `stealth --version` and `stealth doctor fixtures/boot4-clean` run from a new terminal
- [ ] Java 21 dependency handled (installed by Scoop, or a clear message when missing)
- [ ] `scoop update stealth` picks up the next rc
- [ ] README, CONTRIBUTING and the `/release` UAT template include the Scoop install
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- **winget:** worth doing after launch, when releases slow down. JReleaser supports it, but each version waits for Microsoft review.
- **Chocolatey:** moderation delay and admin-only installs. Not worth it while Scoop and winget cover Windows.
- **MSI / bundled JRE via jpackage:** needs WiX and a Windows release job. Revisit with native images, which would remove the Java requirement on every platform.
- **Name change:** the bucket uses the `stealth` placeholder, and moves with the tap when the final name is applied (see [ticket-apply-final-name.md](ticket-apply-final-name.md))

Suggested branch: `feature/CU-<clickup-id>_windows_scoop_install`
Depends on: CI and release pipeline ([ticket-ci-release-pipeline.md](ticket-ci-release-pipeline.md))
