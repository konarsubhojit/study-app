# 6. Bootstrap scope: pure-Kotlin core first

- Status: accepted
- Date: 2026-09-16

## Context

The delivery plan (issues [#2](https://github.com/konarsubhojit/study-app/issues/2)–[#10](https://github.com/konarsubhojit/study-app/issues/10))
starts with the Gradle foundation and then builds the timer vertical slice, because the timer proves
the hardest technical claim in the whole design.

The environment this repository was bootstrapped in cannot resolve `dl.google.com` or
`maven.google.com`. Those hosts serve the Android Gradle Plugin and every AndroidX artifact, so
AGP, Compose, Room, Hilt-Android, WorkManager, Media3 and Glance are all unreachable. The Android
SDK is installed, but without AGP it cannot be driven from Gradle.

Adding an Android module under those conditions produces a repository that does not build at all,
for anyone, including CI.

## Decision

Land the work that is genuinely verifiable, in the order the plan already specifies, and stop at the
point where the network constraint begins:

- **P0 (#2)** — Gradle wrapper, version catalogue, convention plugins, executable module boundaries,
  detekt, Spotless, CI. Complete. The Android, Compose, Hilt and Room convention plugins were added
  once Google's Maven repository became reachable, and verified against a throwaway application,
  feature and database module (see [ADR 0001](0001-build-toolchain.md)); the modules themselves
  still belong to the phases below.
- **P1 (#3)** — `:core:model` and the `:core:common` time abstraction. Complete for the parts the
  domain needs; Room, DataStore and the design system are deferred.
- **P2 (#4, #5, #6)** — the *domain core* of all three hard problems: the timer engine, the
  recurrence and reminder-scheduling engines, and the upload/archive/cache logic, with tests.

This is not a workaround. The architecture (ADR 0002) requires `:core:domain` and `:core:model` to
be Android-free and KMP-ready, so this code was always going to be written as plain Kotlin. Building
it first means the riskiest logic in the product is proven by a fast, deterministic test suite
before any UI exists to obscure it.

What is deliberately **not** attempted: Android modules, Compose UI, Room schemas, Hilt wiring,
foreground services, `AlarmManager` integration and WorkManager workers. Each has a clear seam in
the code — an interface in `:core:common` or a pure planner in `:core:domain` — so the platform
layer is an implementation of an already-tested contract rather than a redesign.

## Consequences

- `./gradlew build` is green today and runs the full verification suite in seconds.
- The version catalogue contains only versions this build has actually resolved, including the
  Android ones — verified rather than guessed.
- The next contributor with network access can add `:app` and `:feature:timer` against a domain
  layer that already handles reboots, clock skew, DST and hostile archives.
- The repository is not yet a runnable Android app. That is stated plainly in the README rather than
  implied by a module skeleton that does not compile.
