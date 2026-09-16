# 6. Bootstrap scope: pure-Kotlin core first

- Status: superseded by [ADR 0001](0001-toolchain.md)
- Date: 2026-09-16

## Context

The delivery plan (issues [#2](https://github.com/konarsubhojit/study-app/issues/2)–[#10](https://github.com/konarsubhojit/study-app/issues/10))
starts with the Gradle foundation and then builds the timer vertical slice, because the timer proves
the hardest technical claim in the whole design.

At the time this decision was made, the bootstrap environment could not resolve Google's Maven
repository. The resulting pure-Kotlin-first scope was appropriate then, but is no longer current
now that the Android build baseline in ADR 0001 is resolvable and verified.

## Decision

Land the work that is genuinely verifiable, in the order the plan already specifies:

- **P0 (#2)** — Gradle wrapper, version catalogue, convention plugins, executable module boundaries,
  detekt, Spotless, CI. Complete.
- **P1 (#3)** — `:core:model` and the `:core:common` time abstraction. Complete for the parts the
  domain needs; Room, DataStore and the design system are deferred.
- **P2 (#4, #5, #6)** — the *domain core* of all three hard problems: the timer engine, the
  recurrence and reminder-scheduling engines, and the upload/archive/cache logic, with tests.

This is not a workaround. The architecture (ADR 0002) requires `:core:domain` and `:core:model` to
be Android-free and KMP-ready, so this code was always going to be written as plain Kotlin. Building
it first means the riskiest logic in the product is proven by a fast, deterministic test suite
before any UI exists to obscure it.

The original decision deferred the Android module and integrations. ADR 0001 supersedes that
portion with a manifest-only `:app`; Compose UI, Room schemas, Hilt wiring, foreground services,
`AlarmManager` integration and WorkManager workers remain deferred.

## Consequences

- `./gradlew build` is green today and runs the full verification suite in seconds.
- The original pure-Kotlin core remains a stable base for Android implementation work.
- Android build-tool versions are now pinned and verified in `gradle/libs.versions.toml`.
