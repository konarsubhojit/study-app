# 1. Build toolchain and dependency pinning

- Status: accepted
- Date: 2026-09-16

## Context

The project needs a build that a new contributor can clone and run, that CI can reproduce
byte-for-byte, and that will not quietly drift as dependencies publish new versions. It also has to
grow from four pure-Kotlin modules today into a multi-module Android app without the build scripts
turning into copy-pasted sludge.

A practical constraint shaped part of this decision: the environment this repository was
bootstrapped in cannot reach `dl.google.com` or `maven.google.com`, so the Android Gradle Plugin and
every AndroidX artifact are unreachable. See [ADR 0006](0006-bootstrap-scope.md).

## Decision

**Gradle 9.7.1 via the wrapper, JDK 21 toolchain, Kotlin 2.x with the K2 compiler.**

The wrapper is committed, so the Gradle version is part of the source tree rather than part of each
developer's machine. A Java *toolchain* is declared rather than relying on `JAVA_HOME`, so the
bytecode target does not depend on which JDK happens to be first on the path.

**All versions live in `gradle/libs.versions.toml`.** No version literal appears in any build
script, including `build-logic`'s own. Only artifacts this build actually resolves are listed, so
every version in the catalogue has been verified against a real build rather than transcribed from
a plan.

**Shared build configuration lives in `:build-logic` as convention plugins**, not in `subprojects {}`
or `allprojects {}` blocks:

| Plugin | Responsibility |
|---|---|
| `studyflow.kotlin.library` | JVM toolchain, explicit API mode, warnings-as-errors, JUnit 5 |
| `studyflow.quality` | detekt and Spotless/ktlint wiring |
| `studyflow.module-boundaries` | fails the build on an illegal project dependency |

Cross-project configuration blocks defeat configuration caching and make a module's real
configuration impossible to read from its own build file. With convention plugins a module's build
script is three lines and says exactly what it is.

**`allWarningsAsErrors` and `ExplicitApiMode.Strict` are on for every module.** A warning that is
allowed to persist is a warning nobody reads. Explicit API mode forces every public declaration to
carry an explicit visibility and return type, which is what makes a library module's surface
reviewable.

**Quality gates run as part of `check`**, so `./gradlew build` is the single command that verifies
everything: compilation, unit tests, detekt, Spotless and the module-boundary rules.

**`.editorconfig` is the single formatting source of truth.** ktlint (via Spotless) and detekt both
read it. Without it ktlint has no line-length limit and re-joins lines that detekt then rejects,
and the two tools fight forever.

## Consequences

- A contributor runs `./gradlew build` and gets the same result as CI, with no local setup beyond a
  JDK.
- Adding a module means adding one line to `settings.gradle.kts` and a three-line build script.
- Bumping a dependency is a one-line change in the catalogue, and Dependabot/Renovate can do it.
- Warnings-as-errors means a compiler upgrade can break the build. That is the point; the
  alternative is discovering the deprecation two years later.
- Android versions are deliberately absent from the catalogue until the Android modules land, so
  that nothing in it is unverified.
