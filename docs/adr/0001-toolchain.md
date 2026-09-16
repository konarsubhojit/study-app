# 1. Build toolchain and dependency pinning

- Status: accepted
- Date: 2026-09-16

## Context

The project needs a build that a new contributor can clone and run, that CI can reproduce
byte-for-byte, and that will not quietly drift as dependencies publish new versions. It also has to
grow from four pure-Kotlin modules today into a multi-module Android app without the build scripts
turning into copy-pasted sludge.

## Decision

**Gradle 9.7.1 via the wrapper, AGP 9.0.1, Kotlin 2.4.20, Android API 36, and JDK 21.**

The wrapper is committed, so the Gradle version is part of the source tree rather than part of each
developer's machine. A Java *toolchain* is declared rather than relying on `JAVA_HOME`, so the
bytecode target does not depend on which JDK happens to be first on the path.

AGP 9.0.1 supports API 36 and requires Gradle 9.1 or newer. API 36 is the latest stable Android
platform at this decision's date. The application namespace and application ID are both fixed as
`dev.studyflow.app`; changing either after release has user-facing and distribution consequences.

The baseline disables AGP's built-in Kotlin support because the existing pure-Kotlin modules use
the standalone Kotlin JVM plugin. This manifest-only app has no Kotlin sources; future Android
Kotlin work must migrate the JVM convention before enabling AGP's built-in Kotlin support.

**All versions live in `gradle/libs.versions.toml`.** No version literal appears in any build
script, including `build-logic`'s own.

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
- The manifest-only `:app` module is a buildable Android baseline; UI and AndroidX dependencies are
  added only with the features that need them.
