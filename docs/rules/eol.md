# `eol`: framework and runtime end of life

Reports the Java release and Spring Boot version a repo uses when their release cycle no longer gets security patches,
or stops getting them within six months. Category: **security** — an end-of-life runtime keeps working, but nothing
that is found in it will ever be fixed.

Dates come from [endoflife.date](https://endoflife.date), through the same 24-hour HTTP cache as the other analyzers
(`~/.stealth/cache/http`). `stealth doctor --offline` uses only cached lookups.

## What it checks

| Subject | Where the version comes from |
|---|---|
| Java | `maven-compiler-plugin`'s `<release>`, else the first of `maven.compiler.release`, `maven.compiler.source`, `maven.compiler.target`, `java.version` the effective model defines |
| Spring Boot | the `spring-boot` version the build manages, through `spring-boot-starter-parent` or the Boot BOM |

Versions are matched on **release cycle**, not exact version: `2.7.18` is checked as Spring Boot `2.7`, and `17.0.2` as
Java `17`, because that's what support dates apply to. A cycle endoflife.date doesn't list is not reported.

Each version is reported once, where it is set. A Java release or Spring Boot version set in a parent POM and inherited
by five modules is one finding, at the parent.

The suggested fix is the **oldest cycle that gets the repo back into support**: the lowest newer cycle that is itself
neither past end of life nor inside the warning window, falling back to the oldest still-supported one. That is
normally a smaller step than "upgrade to the newest release". Where the product sells support beyond the free window
(Spring Boot, Oracle JDK), the finding says until when.

## Configuration

End-of-life dates differ between JDK distributions, and the release a repo *compiles for* isn't necessarily the one it
*runs on*. Both are settable in `.stealth.yml`:

```yaml
eol:
  javaDistribution: oracle-jdk   # default: eclipse-temurin
  javaVersion: "17"              # the runtime, when it isn't the compile target
```

`javaDistribution` is an [endoflife.date product](https://endoflife.date/) that tracks a JDK, such as `eclipse-temurin`,
`amazon-corretto`, `oracle-jdk`, `azul-zulu` or `microsoft-build-of-openjdk`. The choice matters: at the time of
writing, Java 11 is supported until 2027-10-31 on Temurin and ended on 2023-09-30 on Oracle JDK.

Without `javaVersion` the finding is worded "targets Java 11", since stealth only sees the compile target. With it, the
finding is repo-level and worded "runs on Java 11".

## eol-past-end-of-life

`eol/past-end-of-life`, default severity **high**. The cycle's free support ended before today.

> uses Spring Boot 2.7, which reached end of life on 2023-06-30. The oldest supported release is Spring Boot 4.1
> (4.1.1). Commercial support runs to 2029-06-30.

## eol-approaching-end-of-life

`eol/approaching-end-of-life`, default severity **low**. Free support ends within six months. The window opens exactly
six months before the end date, so a repo that is fine today can start warning tomorrow with no change of its own.

> uses Spring Boot 4.1, which reaches end of life on 2027-07-31. Commercial support runs to 2028-07-31.

Both rules cover both products, because severity depends on where the cycle is in its life, not on which product it is
([ADR-0001](../adr/0001-finding-model.md) gives each rule one default severity). To silence one product, use a
`.stealth.yml` severity override on the rule, or allow the Spring Boot component
(`pkg:maven/org.springframework.boot/spring-boot`).

If endoflife.date can't be reached and nothing is cached, the analyzer fails rather than reporting the runtime as
supported.
