# StudyFlow

An Android study-time tracker: a stopwatch that survives anything the platform does to it, a place
to keep study materials of any type, and reminders that actually fire.

This repository currently contains the **build foundation, an Android application baseline, and the
pure-Kotlin domain core**. See [Current state](#current-state) for exactly what is and is not here
yet.

## The three hard problems

Most of the design effort goes into three things that look simple in a feature list and are not.

### 1. A timer that survives app closure

Android will kill the process. The device will reboot, doze for hours, and have its clock corrected
by NTP or shuffled by DST. A stopwatch built on a ticking counter, a wall-clock delta, or a stored
`elapsedMillis` column gets all of these wrong.

StudyFlow **stores events and derives time**. A session is an append-only log of
`STARTED`/`PAUSED`/`RESUMED`/`STOPPED`, and every event records *two* clocks plus a boot id:
monotonic `elapsedRealtime` (immune to clock changes, meaningless across a reboot) and the wall
clock (survives reboots, jumps freely). Elapsed time is recomputed by folding the log, never
accumulated.

Within a boot the monotonic delta is authoritative, so a clock change cannot add or erase study
time. Across a reboot the two are incomparable, so the app refuses to guess: the gap is recorded as
**unverified** and the user is asked, rather than fabricating hours or silently deleting real work.

No `CountDownTimer`, no tick loop, no wake lock — the ticking digits are rendered by the system's
notification chronometer from a single anchor.

→ [ADR 0003](docs/adr/0003-timer-event-sourcing.md) ·
[`TimerEngine`](core/domain/src/main/kotlin/dev/studyflow/core/domain/timer/TimerEngine.kt)

### 2. Object storage for any file type

Pick → stage + SHA-256 → Room row as `PENDING` → resumable chunked upload against **presigned part
URLs** → verify the digest → `SYNCED`. No cloud credentials ever ship in the APK. Part boundaries
are a pure function of file size, so an interrupted 700 MB upload resumes by resending one part, not
the whole file, and the content hash makes uploads idempotent and deduplicated.

Archives are treated as the attacker-controlled data structures they are: entry names are normalised
and checked for zip slip (absolute paths, drive letters, `..` escapes, Windows separators, null
bytes, colliding paths), and entry count, size and compression ratio are capped against zip bombs.

The offline cache is LRU with two hard exceptions — it will never evict a file the user pinned, and
never a file that has not finished uploading, because that local copy is the only copy.

→ [ADR 0005](docs/adr/0005-object-storage.md) ·
[`UploadPlanner`](core/domain/src/main/kotlin/dev/studyflow/core/domain/materials/UploadPlanner.kt) ·
[`ArchiveSafety`](core/domain/src/main/kotlin/dev/studyflow/core/domain/materials/ArchiveSafety.kt) ·
[`CachePlanner`](core/domain/src/main/kotlin/dev/studyflow/core/domain/materials/CachePlanner.kt)

### 3. Reminders that actually fire

There is no single correct Android API for "remind me later", so the choice is a decision matrix:
inexact/WorkManager for gentle nudges, `setExactAndAllowWhileIdle` for precise ones, `setAlarmClock`
plus a full-screen intent for alarm-style. When the OS denies a capability the reminder degrades
*visibly* — the plan reports `EXACT_ALARMS_DENIED`, `NOTIFICATIONS_DENIED` and friends so the UI can
explain rather than silently fail.

Recurrence is an RRULE-lite subset evaluated in **local time**, so "every day at 08:00" stays at
08:00 across DST (the real interval being 23 or 25 hours, which the tests assert), and "the 31st"
clamps to the last day of February instead of being skipped.

→ [ADR 0004](docs/adr/0004-reminder-scheduling.md) ·
[`ReminderScheduler`](core/domain/src/main/kotlin/dev/studyflow/core/domain/reminder/ReminderScheduler.kt) ·
[`RecurrenceCalculator`](core/domain/src/main/kotlin/dev/studyflow/core/domain/reminder/RecurrenceCalculator.kt)

## Talking to the backend

The app/backend contract is written down in [`docs/api/openapi.yaml`](docs/api/openapi.yaml) and
checked by a test: the client calls endpoints declared as values, so a path the specification does
not describe fails the build rather than returning 404 on a user's phone.

Responses are parsed with `ignoreUnknownKeys`, so a field the server adds after a build shipped
cannot break it — an acceptance test proves exactly that. Every failure, from a refused connection
to a `503`, is mapped to a closed set of errors carrying a user-facing message, so no status code
can reach the UI. Retries are exponential with jitter, capped, and limited to idempotent requests,
because replaying a session upload would duplicate the user's study data. A 401 refreshes the token
once and replays the request; a spent refresh token signs the user out instead of looping. Every
response is checked against `X-Minimum-Client-Version`, so a build the server is about to stop
serving gets a friendly upgrade prompt rather than a wall of failures.

Running against a local mock backend is a build flag, not a code change:

```bash
./gradlew installDebug -Pstudyflow.apiBaseUrl=http://10.0.2.2:8080
```

→ [ADR 0007](docs/adr/0007-api-contract-and-network-client.md) ·
[`KtorStudyFlowApi`](core/network/src/main/kotlin/dev/studyflow/core/network/KtorStudyFlowApi.kt) ·
[`ApiErrorMapper`](core/network/src/main/kotlin/dev/studyflow/core/network/error/ApiErrorMapper.kt) ·
[`RetryPolicy`](core/network/src/main/kotlin/dev/studyflow/core/network/retry/RetryPolicy.kt)

## Architecture

Compose UI → ViewModel (UDF/MVI) → pure-Kotlin domain → data, with dependencies pointing inwards
only and Room as the single source of truth for local state.

```
:app
  └── :feature:*          timer, materials, tasks, dashboard, settings
        └── :core:ui, :core:designsystem
        └── :core:domain            ← pure Kotlin, no Android SDK
              └── :core:model, :core:common
        └── :core:database, :core:datastore, :core:network,
            :core:storage, :core:notifications, :core:scheduling
:build-logic               convention plugins (included build)
:benchmark                 macrobenchmark + baseline profiles
```

`:core:model` and `:core:domain` are plain `kotlin("jvm")` modules, so "Android-free" is enforced by
the compiler rather than by review, and the whole domain test suite runs on the JVM in seconds.

The layering rules are **executable**: `./gradlew checkModuleBoundaries` fails the build on an
illegal dependency with an explanation.

```
> Module dependency boundaries violated:
    - :core:model -> :core:common: :core:model must stay dependency-free so every layer
      (and a future KMP target) can share it.
```

→ [ADR 0002](docs/adr/0002-architecture-layering.md)

## Building

Install JDK 21 and Android SDK 37. Use the committed Gradle wrapper; no separate Gradle installation
is required.

```bash
git clone https://github.com/konarsubhojit/study-app.git
cd study-app
./gradlew build          # compile, test, and everything `check` runs
./gradlew check          # ktlint, detekt, Android Lint, module boundaries
./gradlew test           # unit tests only
./gradlew spotlessApply  # fix formatting
```

`./gradlew check` is the single quality entry point: Spotless/ktlint formatting, detekt with the
project ruleset (`config/detekt/detekt.yml`) and the Compose rules, Android Lint with
`warningsAsErrors`, and the module-boundary checks. Baselines cover pre-existing findings only and
are documented in [`CONTRIBUTING.md`](CONTRIBUTING.md), which also describes the opt-in pre-commit
formatting hint.

For module conventions, branch strategy, and the Definition of Done, see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

```bash
# Compose stability and recomposition reports, for performance work only.
./gradlew assembleRelease -Pstudyflow.composeCompilerReports=true
```

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) — no version literal
appears in any build script. Shared configuration lives in `:build-logic` convention plugins
(`studyflow.jvm.library`, `studyflow.android.application`, `studyflow.android.library`,
`studyflow.android.feature`, `studyflow.compose`, `studyflow.hilt`, `studyflow.room`,
`studyflow.test`), so a module's own build file applies a plugin and lists its dependencies, and
adding a module changes nothing but `settings.gradle.kts`.

`allWarningsAsErrors` is on for every module; explicit API mode is on for `:core:*`.

## CI

Every pull request and push to `main`/`master` runs GitHub Actions CI with JDK 21, Gradle caching,
dependency review, committed-secret scanning, and `./gradlew build`. That build is the merge gate for
assemble, unit tests, Detekt, Spotless, Android Lint, and module-boundary checks. Unit test reports and Detekt
SARIF are published as PR annotations, and all test/lint reports are uploaded as artifacts.
Dependency review is advisory: it reports in its job log but cannot block a pull request, because the
action fails outright until the repository's dependency graph is enabled.

The CI job summary records the Gradle build/test/check duration for each run; the Gradle setup
summary records dependency/build-cache hit details. CI credentials must come from GitHub-provided
tokens or repository secrets only; no secrets are committed to this repository.

## Current state

| Area | Status |
|---|---|
| Gradle foundation, convention plugins (JVM, Android, Compose, Hilt, Room), module boundaries, CI | done |
| `:core:model` — sessions, events, time anchors, tasks, recurrence, materials | done |
| `:core:common` — dual-clock time abstraction, dispatchers | done |
| `:core:domain` — timer, recurrence, reminder scheduling, upload, archive safety, cache | done |
| `:core:network` — OpenAPI contract, typed Ktor client, auth refresh, retries, error mapping | done |
| `:core:testing` — `FakeDevice` (reboot / deep sleep / clock jump simulation), `FakeStudyFlowBackend` | done |
| `:app` — Android application baseline | done |
| Compose UI, Room, Hilt, foreground service, WorkManager, AlarmManager | not yet |

`:app` is intentionally manifest-only while the Android UI and platform integrations are deferred,
but the build that will carry them is in place: the Android, Compose, Hilt and Room convention
plugins were verified against a throwaway app, feature and database module before it was deleted.
The domain core remains Android-free, so building it first proves the riskiest logic before any UI
exists to obscure it. Each platform concern has a seam waiting for it — an interface in
`:core:common` or a pure planner in `:core:domain`.

→ [ADR 0006](docs/adr/0006-bootstrap-scope.md)

## Delivery plan

Tracked as a hierarchy of GitHub issues, one master issue and nine epics.

| Phase | Epic |
|---|---|
| P0 Foundation — build, modules, CI | [#2](https://github.com/konarsubhojit/study-app/issues/2) |
| P1 Core platform — design system, nav, Room, DataStore, time, notifications | [#3](https://github.com/konarsubhojit/study-app/issues/3) |
| P2 MVP slices — timer, tasks/reminders, materials | [#4](https://github.com/konarsubhojit/study-app/issues/4), [#6](https://github.com/konarsubhojit/study-app/issues/6), [#5](https://github.com/konarsubhojit/study-app/issues/5) |
| P3 Cloud — auth, BFF, presigned URLs, offline-first sync | [#7](https://github.com/konarsubhojit/study-app/issues/7) |
| P4 Delight — dashboard, stats, streaks, widgets | [#8](https://github.com/konarsubhojit/study-app/issues/8) |
| P5 Hardening — tests, performance, battery, a11y, security | [#9](https://github.com/konarsubhojit/study-app/issues/9) |
| P6 Release — Play policy, staged rollout, observability | [#10](https://github.com/konarsubhojit/study-app/issues/10) |

## Decision records

- [ADR template](docs/adr/template.md)
- [0001 — Build toolchain and dependency pinning](docs/adr/0001-toolchain.md)
- [0002 — Architecture layering and module boundaries](docs/adr/0002-architecture-layering.md)
- [0003 — The timer is event-sourced and clock-derived](docs/adr/0003-timer-event-sourcing.md)
- [0004 — Reminders use a scheduling decision matrix](docs/adr/0004-reminder-scheduling.md)
- [0005 — Object storage: presigned URLs, content addressing, untrusted archives](docs/adr/0005-object-storage.md)
- [0006 — Bootstrap scope: pure-Kotlin core first](docs/adr/0006-bootstrap-scope.md)
