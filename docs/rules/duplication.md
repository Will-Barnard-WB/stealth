# `duplication`: copy-pasted code

Reports blocks of Java code that are copy-pasted in more than one place. Every copy has to be fixed separately, and
sooner or later one of them isn't. AI agents make it worse by regenerating helpers that already exist. Category:
**tech**.

Detection is [PMD CPD](https://pmd.github.io/pmd/pmd_userdocs_cpd.html) (the copy-paste detector, PMD 7), run in the
same process. It needs no network and no build.

## What it scans

- `.java` files under `src/main/java` in every module, at any depth: `src/main/java`, `api/src/main/java`,
  `services/billing/src/main/java`, and so on.
- Only files git tracks or would track: gitignored files are skipped. Outside a git repository, every file under the
  root is considered.
- Never `target/`, `generated-sources/` or `generated-test-sources/`: generated code isn't edited by hand.
- Tests (`src/test/java`) are skipped by default, since test setup is often repeated on purpose. See
  [Configuration](#configuration).
- Non-Java code isn't scanned yet.

## How blocks are compared

CPD splits each file into tokens (keywords, identifiers, operators, literals) and finds token sequences that appear
more than once. Comments and formatting never count.

| Setting | Value | Effect |
|---|---|---|
| Minimum size | 100 tokens | Smaller repeats (a two-line null check) aren't reported. About 10–15 lines of ordinary code. |
| Literals | ignored | `"Total: "` and `"Sum: "`, or `2` and `3`, count as the same, so a copy with changed messages is still a copy. |
| Annotations | ignored | `@Override`, `@Transactional` and the like don't break a match. |
| Identifiers | compared | A copy with every variable renamed isn't matched. Catching those is Phase 3's duplicate-intent detection. |

When a block has been copied to several places and the copies have drifted a little, CPD finds several overlapping
matches (A–B over 28 lines, A–C over 30). Matches that share any lines are merged into **one** finding listing every
copy, so the same code is never reported twice.

## duplication-cpd

`duplication/cpd`, default severity **low**, or **medium** when the block is 400 tokens or more (about 50 lines of
code) or is copied to 3 or more places. Size is measured in tokens rather than lines so that Javadoc and blank lines
don't make a block look bigger than it is.

The finding points at the first copy (by path, then line), and lists the others as related locations:

> 28 lines (236 tokens) at lines 10-37 are duplicated at src/main/java/com/example/dup/report/ReportSummary.java:10-37.

With more than four copies, the message names three others and counts the rest ("and 2 more places").

**Fix:** extract the shared code into one method or class and call it from each place.

**Fingerprint** (ADR-0001): the sorted paths of the copies plus a hash of the first copy's code with whitespace removed.
Moving the block within its files or reformatting it keeps the fingerprint. Editing the duplicated code, adding or
removing a copy, or renaming or moving a file that holds one changes it.

## Configuration

Per repository, in `.stealth.yml`:

```yaml
analyzers:
  duplication:
    min-tokens: 150       # default 100: the smallest duplicated block reported, in tokens
    include-tests: true   # default false: also scan src/test/java
```

The defaults for every repo can also be changed with the environment variables `STEALTH_DUPLICATION_MIN_TOKENS` and
`STEALTH_DUPLICATION_INCLUDE_TESTS`; `.stealth.yml` wins over them.

## Performance

CPD holds every scanned file's tokens in memory and runs in one thread. It runs under the doctor's per-analyzer timeout
(60 s by default); when the timeout is hit, or the JVM runs out of memory, the analyzer is reported as timed out or
failed and the rest of the report is unaffected.

Measured on 2026-09-30 (Windows 11, JDK 21, shallow clones):

| Repository | Java files scanned | Time | Heap needed | Findings |
|---|---|---|---|---|
| spring-petclinic | 30 | 0.1 s | — | 0 |
| spring-framework | 5,464 | 2.1 s | 256–512 MB | 236 (37 medium, 199 low) |
| spring-framework, tests included | 8,952 | 4.0 s | over 1 GB | 915 |

Scanning tests roughly doubles the time and needs several times the memory, which is one more reason it's opt-in.
