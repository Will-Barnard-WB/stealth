# `secrets`: hardcoded credentials

Reports credentials committed to the working tree: cloud keys, API tokens, private keys, and passwords or secrets
assigned in configuration and code. Category: **security** — anyone who can read the repo can use them.

Precision comes first. A secrets scanner that cries wolf gets switched off, so values that only *look* like secrets
(documentation examples, `${ENV_VAR}` references, UUIDs, commit hashes, checksums) are not reported.

## Output safety

The secret itself is never printed or serialized. A finding shows a **masked preview**: the first four characters
followed by a fixed `********`, which doesn't give away the length either.

> AWS access key id (AKIA\*\*\*\*\*\*\*\*) is hardcoded.

The fingerprint is `sha256(ruleId, path, sha256(secret))` ([ADR-0001](../adr/0001-finding-model.md)): stable across
runs and line moves, without storing the value. The same secret in two files is two findings.

## What it scans

Files git tracks or would track: tracked files, plus untracked files that no `.gitignore` excludes. When the path is
not inside a git repository, every file under it. In both cases it skips:

- `.git/` and `target/` directories (ADR-0004's always-ignored paths), symlinks and submodules
- binary files (a NUL byte in the first 8000 bytes, as git decides)
- files over 1 MB
- lockfiles (`package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`, `gradle.lockfile`, `go.sum`, …), which are full of
  random-looking hashes

Files are searched in parallel. Each rule has keywords, and a file is only searched by a rule's regexes if it contains
one of them.

Git **history** is not scanned yet: a secret deleted in the latest commit is still in history, and a follow-up adds
history scanning with JGit.

## Rules

Every rule skips obvious placeholders: values containing `EXAMPLE`, `changeme`, `placeholder`, `dummy`, `redacted`,
`your_` / `your-`, `xxxxxx`, `******`, `<…>`, `${…}`, `#{…}`, `{{…}}`, or a single repeated character.

The ruleset is bundled in [`rules.yml`](../../core/src/main/resources/dev/stealth/core/secrets/rules.yml). It was
written for stealth from the providers' published token formats, not copied from another scanner.

| Rule | Severity | Finds |
|---|---|---|
| `secrets/aws-access-key-id` | critical | `AKIA`/`ASIA`/`ABIA`/`ACCA` + 16 base32 characters |
| `secrets/aws-secret-access-key` | critical | 40-character key assigned to `aws_secret_access_key` (entropy ≥ 4.0) |
| `secrets/github-token` | critical | `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_` tokens |
| `secrets/github-fine-grained-token` | critical | `github_pat_` tokens |
| `secrets/stripe-secret-key` | critical | `sk_live_` / `rk_live_` keys (test-mode keys aren't reported) |
| `secrets/gcp-service-account-key` | critical | a service account key file's `private_key_id` |
| `secrets/private-key` | critical | PEM/OpenSSH `-----BEGIN … PRIVATE KEY-----` blocks with a body (public keys and certificates aren't reported) |
| `secrets/slack-token` | high | `xoxb-`, `xoxp-`, `xoxa-`, … tokens |
| `secrets/slack-webhook-url` | high | `https://hooks.slack.com/services/T…/B…/…` |
| `secrets/google-api-key` | high | `AIza` + 35 characters |
| `secrets/generic-secret` | high | a high-entropy value assigned to a password-like name (below) |
| `secrets/jwt` | medium | signed JSON Web Tokens (`eyJ….eyJ….…`) |

When two rules match the same value, the more specific one wins: a GitHub token assigned to `TOKEN` is one
`secrets/github-token` finding, not also a generic one.

### secrets-generic-secret

A value assigned to a name containing `password`, `passwd`, `pwd`, `secret`, `token`, `api-key`, `access-key`,
`private-key` or `credential`, in:

| Files | Form |
|---|---|
| `.properties`, `.yml`, `.yaml`, `.env` | `db.password=…`, `password: …` |
| `.java`, `.kt`, `.kts`, `.groovy`, `.scala` | `String apiKey = "…"` (string literals only) |
| `.xml` (Maven `settings.xml`) | `<password>…</password>` |

It's only reported when the value has **Shannon entropy ≥ 3.5 bits per character** and at least 8 characters, and
doesn't look like a name rather than a secret:

- a URL (`https://oauth2.googleapis.com/token`) or a path (`/etc/…`, `./…`, `~/…`)
- an environment variable or constant name (`GITHUB_TOKEN`)
- a dotted or colon-separated identifier (`org.example.TokenFilter`, `classpath:keystore.jks`)
- a UUID
- a hex string assigned to a name with `sha`, `md5`, `hash`, `checksum`, `digest` or `fingerprint` in it

In practice this means short or dictionary passwords (`password123`) are not reported. That's a deliberate trade-off:
entropy is what separates real secrets from configuration values.

## Suppressing a finding

**Inline:** put `stealth:allow` on the secret's line, in any comment syntax, or on a comment line directly above it
(for `.properties` and PEM files, which have no end-of-line comments):

```java
private static final String TOKEN = "ghp_…"; // stealth:allow revoked test token
```

```properties
# stealth:allow sandbox account, no access to production
aws.access-key-id=AKIA…
```

**In `.stealth.yml`** ([ADR-0004](../adr/0004-stealth-yml-format.md)), by path glob (gitignore-style) or fingerprint,
optionally limited to some rules:

```yaml
allow:
  secrets:
    - path: "src/test/resources/**"
      reason: Test fixtures, not real keys
    - fingerprint: "v1:c77e0939…"
      reason: Key revoked on 2026-08-14
      expires: 2026-12-31
    - path: "**/application-local.yml"
      rules: [secrets/generic-secret]
      reason: Local dev passwords for the docker-compose database
```

An entry with both `path` and `fingerprint` must match both. After `expires` (UTC) it stops applying.

The analyzer already applies these entries (`StealthConfig.secrets()`); reading them from `.stealth.yml` arrives with
the `.stealth.yml` config ticket.

## Fixing a finding

Revoke and rotate the credential first: once it's committed, assume it's leaked. Then load it from the environment or
a secret store (`${DB_PASSWORD}` in Spring configuration). Deleting it from the file doesn't remove it from git history.
