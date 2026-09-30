# `stealth doctor` analyzer and severity filters

ClickUp: _not yet created_

### What is the problem we are trying to solve?

`stealth doctor` always runs every analyzer and shows every finding. A user who only wants to check for leaked secrets, or only wants to see the critical problems, has to wait for the whole run and read past everything else.

### Who is impacted?

CLI users, especially those running `doctor` repeatedly while fixing one kind of problem.

### Why it's important or urgent?

It makes `doctor` faster and easier to use while working through one kind of problem, and it lays the groundwork for CI use, where teams often gate on one category or on critical findings only.

### What is the proposed solution?

Flags on `stealth doctor` that pick which analyzers run and which severities are shown. They are listed in `stealth doctor --help` under two short headings, and the root `stealth` screen is unchanged.

```
Run only:
      --security      Security analyzers
      --tech          Tech analyzers
      --deps          Outdated dependencies
      --vuln          Known vulnerabilities
      --eol           End-of-life frameworks and runtimes
      --secrets       Leaked secrets
      --maintenance   Unmaintained dependencies
      --duplication   Duplicated code

Show only:
      --critical      Critical findings
      --high          High findings
      --medium        Medium findings
      --low           Low findings
      --info          Info findings
```

- Flags combine as a union: `--secrets --vuln` runs both, and `--security --duplication` runs every security analyzer plus duplication. With no run flag, every analyzer runs.
- Each severity flag matches one exact level: `--critical --high` shows critical and high only. With no severity flag, every finding is shown.
- Analyzers that a filter leaves out are hidden from the status line. Analyzers disabled in `.stealth.yml` still show as skipped.
- If the severity filter leaves nothing, the report says "No critical or high findings." instead of "No problems found."

### Technical approach

- **`core`**: `AnalyzerRunner.run(RepoContext, Predicate<Analyzer>)` runs only the selected analyzers and leaves the rest out of `DoctorReport.results()`. `run(RepoContext)` still runs them all.
- **`cli`**: `DoctorCommand` has two picocli `@ArgGroup`s. `RunOnly` builds the analyzer predicate from ids and categories, and `ShowOnly` builds the severity set. The findings are filtered by severity before rendering.
- `abbreviateSynopsis` keeps the usage line to `stealth doctor [OPTIONS] [PATH]`, and `sortOptions = false` keeps flags in declared order.
- `TerminalReport.render(..., Set<Severity>)` names the chosen severities when none are left.

### Definition of done

- [x] `AnalyzerRunnerTest`: an unselected analyzer doesn't run and is absent from the results
- [x] `DoctorCommandTest`: single analyzer, analyzer plus category, severity filtering, the empty-after-filter message, and the help layout
- [x] `./mvnw verify` passes (tests + Spotless)

### Notes from implementation

- **Adding an analyzer:** a new analyzer needs its own flag in `DoctorCommand.RunOnly`, because the flag-to-id mapping is written out by hand. `--security` and `--tech` pick it up automatically.
- **Severity filtering happens after the run**, so it doesn't make `doctor` faster; only the run flags do.
