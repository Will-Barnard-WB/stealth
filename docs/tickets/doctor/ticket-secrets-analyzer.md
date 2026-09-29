# Secrets analyzer (working tree)

ClickUp: _not yet created_

### What is the problem we are trying to solve?

Hardcoded credentials (cloud keys, tokens, private keys, passwords in `application.yml`) are common in older Spring repos and are a direct security risk. `stealth doctor` should find them in the working tree without drowning users in false positives from UUIDs, hashes and test data.

### Who is impacted?

CLI and CI users, and security buyers evaluating the security half of the score.

### Why it's important or urgent?

It's one of the highest-severity findings we can produce, and precision matters more here than anywhere: a noisy secrets scanner gets switched off.

### What is the proposed solution?

A security finding per detected secret, with file, line, rule (e.g. "AWS access key") and a **masked** preview. Users can allowlist false positives in `.stealth.yml` or with an inline comment.

### Technical approach

- **`core`**: `SecretsAnalyzer` (category security) + `SecretRule` definitions loaded from a bundled YAML ruleset
- **Rules**: AWS access keys, GitHub tokens (`ghp_`, `github_pat_`, …), Slack, Stripe, Google API keys / service-account JSON, PEM private key blocks, JWTs, and generic `password|secret|token = <value>` assignments in `.properties`/`.yml`/`.java`, which only fire above a Shannon-entropy threshold
- **Files**: only files git tracks or would track (JGit, respecting `.gitignore`). Skip binaries, files over 1MB, `target/`, lockfiles and ignored paths from `.stealth.yml`
- **Allowlist**: `secrets.allow` in `.stealth.yml` (by fingerprint or path glob), plus an inline `stealth:allow` comment on the line. Obvious placeholders (`changeme`, `${ENV_VAR}`, `EXAMPLE`) are ignored by default
- **Output safety**: never print or serialize the full secret. Mask all but the first 4 characters. The fingerprint is a SHA-256 hash of the value
- Run file scanning in parallel. Precompiled regexes
- **Risk**: licensing if we borrow rules from gitleaks (MIT) or similar. Attribute properly in NOTICE

### Definition of done

- [ ] Unit tests per rule: matches and near-misses
- [ ] Precision tests against `fixtures/with-secrets`: every real pattern flagged, every look-alike (UUIDs, hashes, `EXAMPLE` keys, env placeholders) **not** flagged, and `fixtures/boot4-clean` produces **no** findings
- [ ] Test that no renderer output (terminal, JSON, SARIF) contains an unmasked secret
- [ ] Allowlist tests (config fingerprint, path glob, inline comment)
- [ ] `./mvnw verify` passes (tests + Spotless)

### Out of scope

- Git history scanning: follow-up ticket, built with JGit
- Verifying secrets against provider APIs (is the key live?): not planned for the CLI

Suggested branch: `feature/CU-<clickup-id>_secrets_analyzer`
Depends on: analyzer SPI, fixture repos
