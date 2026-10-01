# `hygiene`: how the repository is run

Reports debt in how the repository is looked after rather than in the code: no ownership, no CI, untested modules,
large committed files and abandoned branches. They're cheap to fix and strong signs of a repo nobody is watching.
Category: **tech**, all **low**. Run it alone with `stealth doctor --hygiene`.

## hygiene-missing-codeowners

`hygiene/missing-codeowners`. None of `CODEOWNERS`, `.github/CODEOWNERS` or `docs/CODEOWNERS` exists, so nobody is
asked to review changes automatically. Fix: add `.github/CODEOWNERS`.

## hygiene-missing-ci

`hygiene/missing-ci`. No CI configuration: no `.github/workflows/*.yml` (or `.yaml`), `.gitlab-ci.yml`,
`Jenkinsfile`, `azure-pipelines.yml`, `.circleci/config.yml` or `bitbucket-pipelines.yml`.

## hygiene-missing-tests

`hygiene/missing-tests`. A module has `.java` files under `src/main/java` but none under `src/test/java`. Reported
once per module, at the module's `pom.xml`. Without a Maven build, the repository root is checked as one module.

## hygiene-stale-branches

`hygiene/stale-branches`. Local and remote-tracking branches whose newest commit is older than 90 days, as one finding
listing their names. The checked-out branch, the default branch (`origin/HEAD`), `main` and `master` don't count, and
a branch and its `origin/` copy count once.

Branches are only checked when stealth runs on the root of a git work tree. In a subdirectory (one service in a
monorepo) the branches belong to the whole repository, so they're left alone; the other checks still run.

## hygiene-large-file

`hygiene/large-file`. A file over 5 MB that git tracks, or would track because `.gitignore` doesn't exclude it. Large
files bloat every clone; move them to Git LFS or an artifact store.

## Thresholds

Until `.stealth.yml` can set them per repository, change them for a run with
`-Dstealth.hygiene.stale-branch-after=P180D` and `-Dstealth.hygiene.large-file-bytes=10485760`.
