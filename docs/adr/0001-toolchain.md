# 1. Build toolchain and dependency pinning

- Status: accepted
- Date: 2026-09-16

## Context

The project needs a build that a new contributor can clone and run, that CI can reproduce
byte-for-byte, and that will not quietly drift as dependencies publish new versions. It also has to
grow from four pure-Kotlin modules today into a multi-module Android app without the build scripts
turning into copy-pasted sludge.

## Decision

**Gradle 9.7.1 via the wrapper, AGP 9.4.0, Kotlin 2.4.20 with the K2 compiler, Android API 37, and
JDK 21.**

The wrapper is committed, so the Gradle version is part of the source tree rather than part of each
developer's machine. A Java *toolchain* is declared rather than relying on `JAVA_HOME`, so the
bytecode target does not depend on which JDK happens to be first on the path.

`compileSdk` is 37 because Compose BOM 2026.09.00 refuses to be consumed by a module compiled
against an older platform. `:app`'s namespace and application ID are both `dev.studyflow.app`, and
neither is written in a build file: the conventions derive the namespace from the module path and
the application ID from the namespace. The application ID is the identity the Play Store and every
installed device use, so pinning it in one derived place is what stops it being changed casually.

**AGP's built-in Kotlin support is used, not disabled.** From AGP 9 the Android plugins compile
Kotlin themselves, so the Android conventions do not apply `org.jetbrains.kotlin.android`; the
`android.builtInKotlin=false` opt-out is removed in AGP 10 anyway. The pure-Kotlin `:core:*` modules
are unaffected — they use the standalone Kotlin JVM plugin, which is a per-module choice.

**All versions live in `gradle/libs.versions.toml`.** No version literal appears in any build
script, including `build-logic`'s own.

**Shared build configuration lives in `:build-logic` as convention plugins**, not in `subprojects {}`
or `allprojects {}` blocks:

| Plugin | Responsibility |
|---|---|
| `studyflow.jvm.library` | Pure-Kotlin module: JVM toolchain, compiler options, quality and test gates |
| `studyflow.android.application` | The APK: SDK levels, packaging, R8-minified release build type |
| `studyflow.android.library` | Android library module |
| `studyflow.android.feature` | Android library + Compose + Hilt + the dependencies every feature needs |
| `studyflow.compose` | Compose compiler, BOM-aligned dependencies, on-demand compiler metrics |
| `studyflow.hilt` | Hilt plugin and KSP processor |
| `studyflow.room` | Room plugin, KSP processor, exported schemas |
| `studyflow.test` | JUnit 5 everywhere, Android unit-test options, instrumentation dependencies |
| `studyflow.quality` | detekt and Spotless/ktlint wiring |
| `studyflow.module-boundaries` | fails the build on an illegal project dependency |

Cross-project configuration blocks defeat configuration caching and make a module's real
configuration impossible to read from its own build file. With convention plugins a module's build
script is three lines and says exactly what it is.

A feature module's whole build file is `plugins { id("studyflow.android.feature") }` plus the
dependencies that are actually specific to it, and adding one touches no file above it except
`settings.gradle.kts`. The root build script declares the third-party plugins with `apply false`,
which is only how they reach the shared build classpath so `:build-logic` can apply them by id — it
configures nothing.

**Kotlin compiler options are shared, but explicit API mode is not.** `:core:*` modules publish an
API every other layer compiles against, so their surface must be spelled out. Requiring the same of
every `@Composable` would add ceremony without adding a reader.

**Compose compiler metrics are opt-in**, via `-Pstudyflow.composeCompilerReports=true`. Writing the
reports defeats compilation avoidance, so they are a flag for performance work rather than a
permanent build-file edit that nobody remembers to revert.

**KSP, never kapt.** kapt has to generate Java stubs for every Kotlin source before an annotation
processor sees them; KSP reads the Kotlin AST directly.

**`allWarningsAsErrors` is on for every module.** A warning that is allowed to persist is a warning
nobody reads. Explicit API mode forces every public declaration to carry an explicit visibility and
return type, which is what makes a `:core:*` module's surface reviewable.

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
- The Android, Compose, Hilt and Room versions in the catalogue were verified by building a
  throwaway application, feature and Room module against these conventions — an APK, an R8-minified
  release, Android Lint, JUnit 5 unit tests, an exported Room schema and Compose reports — which was
  then deleted. Nothing in the catalogue is a guess.
- `:app` is a manifest-only Android baseline that builds today; UI and AndroidX dependencies arrive
  with the features that need them, and its build file stays two lines when they do.
