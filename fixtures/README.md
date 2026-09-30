# Fixture repos

Small, deterministic Maven projects that `stealth doctor` analyzers are tested against. Each one represents a kind of
repo stealth has to handle, and this file lists what each one **must** and **must not** trigger. Analyzer precision
tests assert against these lists; if you change a fixture, update this file in the same PR.

| Fixture | What it is | Expected findings |
|---|---|---|
| [`boot2-legacy`](boot2-legacy) | Spring Boot 2.7, Java 11, `javax.servlet`, pinned outdated and vulnerable dependencies | outdated deps, known vulnerabilities, Spring Boot EOL, unmaintained dep, missing CODEOWNERS/CI |
| [`boot4-clean`](boot4-clean) | Spring Boot 4.1, Java 21, CODEOWNERS, CI config, tests | **none**: the false-positive baseline |
| [`with-secrets`](with-secrets) | Fake credentials plus look-alikes | secrets only for the real patterns |
| [`duplicated`](duplicated) | Copy-pasted code above and below the CPD threshold | one duplication group |
| [`multi-module`](multi-module) | Parent POM + BOM import + 3 modules, versions from the parent, properties and overrides | correct effective versions per module; no duplicate findings per module |

## Rules for using fixtures

- **Read-only.** Never write into `fixtures/` from a test. Anything that modifies files copies the fixture into a
  `@TempDir` first: `Fixture.BOOT2_LEGACY.copyTo(tempDir)` (see `core/src/test/java/dev/stealth/core/Fixture.java`).
- **No live data.** "Latest version", "end of life" and "known vulnerability" all change over time. Tests use an
  injected `Clock` and recorded or stubbed responses (WireMock) for Maven Central, OSV.dev and endoflife.date, never
  the live APIs.
- **Reference date: 2026-09-29.** The expectations below were checked against live data on that date. Recorded
  responses should be captured on (or pinned to) that date too.
- **Not part of our build.** Fixtures aren't modules of the root POM, so their dependencies (including the deliberately
  vulnerable ones) never end up in stealth's artifacts. They're excluded from Dependabot updates and GitHub secret
  scanning (`.github/dependabot.yml`, `.github/secret_scanning.yml`).
- Only `boot2-legacy` and `boot4-clean` have hygiene expectations. The other three have no CODEOWNERS, CI or tests,
  so hygiene findings on them are expected and not part of their precision tests.
- Each fixture builds on its own: `./mvnw -f fixtures/<name>/pom.xml verify` (needs network the first time).

Line numbers below are 1-based. Where a finding is about a dependency version, the line is where the version value is
actually written, which is what `declaredAt` should point to.

---

## `boot2-legacy`

A typical neglected Spring Boot 2 service. Also the input for Phase 2 upgrade tests (`javax.servlet` →
`jakarta.servlet` in `RequestIdFilter`), so it has a passing test.

### Must flag

**End of life** (`pom.xml`)

| Subject | Line | Why |
|---|---|---|
| Spring Boot 2.7 | 10 | OSS support ended 2023-06-30 |
| Java 11 | 19 | Only with `eol.javaDistribution: oracle-jdk` (premier support ended 2023-09-30). With the default Temurin data, Java 11 is supported until 2027-10-31, so it's **not** flagged at the reference date and becomes an "EOL within 6 months" warning from 2027-04-30. |

**Known vulnerabilities in direct dependencies** (OSV.dev at the reference date)

| Dependency | Line | Advisory | Severity | Fixed in |
|---|---|---|---|---|
| `org.apache.commons:commons-text:1.9` | 40 | GHSA-599f-7c49-w659 (CVE-2022-42889, "Text4Shell") | critical | 1.10.0 |
| `com.google.guava:guava:30.1-jre` | 35 | GHSA-7g45-4rm6-3mm3 (CVE-2023-2976) | moderate | 32.0.0-jre |
| `com.google.guava:guava:30.1-jre` | 35 | GHSA-5mg8-w23w-74h3 (CVE-2020-8908) | low | 32.0.0-jre |
| `org.apache.commons:commons-lang3:3.9` | 30 | GHSA-j288-q9x7-2f5v (CVE-2025-48924) | moderate | 3.18.0 |

commons-text is the deliberately planted one. OSV lists guava's fix as `32.0.0-android`; the analyzer should report
the `-jre` flavour matching the version in use.

Spring Boot 2.7.18 also brings in many **transitive** dependencies with known advisories (Spring Framework 5.3,
Tomcat 9, SnakeYAML 1.30 and others). Those aren't listed here: tests assert them against the recorded OSV responses.

**Outdated dependencies** (against the stubbed "latest" snapshot)

| Dependency | Line |
|---|---|
| `spring-boot-starter-parent:2.7.18` | 10 (Boot-managed starters collapse into this one finding) |
| `commons-lang3:3.9` | 30 |
| `guava:30.1-jre` | 35 |
| `commons-text:1.9` | 40 |

**Unmaintained dependencies**

| Dependency | Line | Why |
|---|---|---|
| `commons-collections:commons-collections:3.2.2` | 45 | Last release 2015; replaced by `commons-collections4`. It's already the latest 3.x, so it must **not** be reported as outdated. |

**Repo hygiene**: missing CODEOWNERS, missing CI configuration.

### Must not flag

- Secrets, duplication.
- "Missing tests": `src/test/java` has a test.

---

## `boot4-clean`

The false-positive baseline. **Every analyzer must produce zero findings here.** It uses the plain
`spring-boot-starter` rather than the web starter because, at the reference date, even the latest Boot 4.1.1 web stack
pulls in advisories (Tomcat 11.0.24, Jackson 3.1.5). All 50 dependencies, test scope included, had no OSV advisories
at the reference date.

| Check | Why it's clean |
|---|---|
| Freshness | Spring Boot 4.1.1 (latest at the reference date); no dependencies outside Boot's management |
| Vulnerabilities | None in the full dependency tree |
| End of life | Spring Boot 4.1 supported until 2027-07-31; Java 21 (Temurin) until 2029-12-31. Boot 4.1 enters the 6-month warning window on 2027-01-31, so tests must use a fixed `Clock`. |
| Hygiene | `.github/CODEOWNERS`, `.github/workflows/ci.yml` and `src/test/java` all present |
| Secrets, duplication | None |

The scoring engine's golden test expects ≥ 95 in both categories.

---

## `with-secrets`

All values are fake, generated from a fixed seed. None are real credentials.

### Must flag

| File | Line | Rule |
|---|---|---|
| `src/main/resources/application.properties` | 5 | AWS access key id |
| `src/main/resources/application.properties` | 6 | generic secret assignment (high entropy) |
| `src/main/java/com/example/secrets/GitHubClient.java` | 6 | GitHub personal access token (`ghp_`) |
| `src/main/resources/deploy-key.pem` | 1 | private key block |

### Must not flag

| File | Line | Look-alike |
|---|---|---|
| `src/main/resources/application.properties` | 9 | AWS's documented example key (`…EXAMPLE`) |
| `src/main/resources/application.properties` | 10 | environment placeholder `${DB_PASSWORD}` |
| `src/main/resources/application.properties` | 11 | placeholder password `changeme` |
| `src/main/resources/application.properties` | 12 | UUID |
| `src/main/resources/application.properties` | 13 | git commit SHA |
| `src/main/java/com/example/secrets/GitHubClient.java` | 8 | `TOKEN_ENV_VAR = "GITHUB_TOKEN"` (a name, not a value) |
| `src/main/resources/public-key.pem` | 1 | public key block |
| `.mvn/wrapper/maven-wrapper.properties` | 2 | SHA-256 checksum |

No other analyzer should report anything beyond hygiene (it has no CODEOWNERS, CI or tests).

---

## `duplicated`

Checked with PMD CPD 7 (via `maven-pmd-plugin` 3.26.0) at the default threshold of 100 tokens.

### Must flag

Exactly **one** duplication group, 236 tokens / 28 lines:

| File | Lines |
|---|---|
| `src/main/java/com/example/dup/invoice/InvoiceSummary.java` | 10–37 |
| `src/main/java/com/example/dup/report/ReportSummary.java` | 10–37 |

### Must not flag

| File | Why |
|---|---|
| `text/NameCheck.java`, `text/ValueCheck.java` | identical `isBlank` helper, 29 tokens: below the threshold (flagged only at ≤ 29 tokens) |
| `export/ExportSummary.java` | same logic as `InvoiceSummary` with every variable renamed; not matched while identifiers are compared |

---

## `multi-module`

Parent POM (`pom.xml`) with three modules: `api` ← `service` ← `app`. Tests the Maven model loading: effective
versions per module, and where each version is actually set. All dependencies had no OSV advisories at the
reference date.

### Effective versions (verified with `mvn dependency:list`)

| Module | Dependency | Effective version | Set at | How |
|---|---|---|---|---|
| `api` | `jackson-databind` | 2.22.3 | `pom.xml:21` | `jackson-bom` import, BOM version from a property |
| `api` | `slf4j-api` | 2.0.20 | `pom.xml:23` | parent `dependencyManagement` via property |
| `service` | `guava` | 33.6.0-jre | `service/pom.xml:17` | module overrides the parent's `guava.version` property |
| `service` | `commons-lang3` | 3.20.0 | `pom.xml:48` | parent `dependencyManagement`, literal version |
| `service` | `api` | 1.0.0-SNAPSHOT | — | internal module |
| `app` | `guava` | 33.7.2-jre | `pom.xml:22` | parent `dependencyManagement` via property |
| `app` | `commons-lang3` | 3.19.0 | `app/pom.xml:27` | explicit version overrides management |
| `app` | `slf4j-api` | 2.0.20 | `pom.xml:23` | parent `dependencyManagement` via property |
| `app` | `service` | 1.0.0-SNAPSHOT | — | internal module |

### Expectations

- Internal modules (`api`, `service`) are never looked up on Maven Central or OSV.
- A dependency whose version is set in one place produces **one** finding at that place, not one per module:
  `slf4j-api` is used by `api` and `app` but reported once, at `pom.xml:23`.
- `guava` and `commons-lang3` resolve to different versions in different modules, so they can produce separate
  findings, each at the line where that module's version is set.
- Freshness depends on the stubbed "latest" snapshot: at the reference date, `guava` 33.6.0-jre (`service`,
  `service/pom.xml:17`), `commons-lang3` 3.19.0 (`app`, `app/pom.xml:27`) and `commons-lang3` 3.20.0 (managed at
  `pom.xml:48`, used by `service`) are behind the latest releases (guava 33.7.2-jre, commons-lang3 3.21.0, released
  2026-09-25); everything else is current.
