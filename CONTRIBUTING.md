# Contributing to StudyFlow

## Set up the project

Install JDK 21 and Android SDK 37, then clone and verify the repository:

```bash
git clone https://github.com/konarsubhojit/study-app.git
cd study-app
./gradlew build
```

Use the committed Gradle wrapper; a separate Gradle installation is neither required nor supported.
See the [README](README.md) for the current module map and
[ADR 0002](docs/adr/0002-architecture-layering.md) for dependency rules.

## Module layout

- `:app` wires Android application concerns together.
- `:feature:*` owns a user-facing vertical slice and may depend on `:core:*`, never another feature.
- `:core:model` contains dependency-free shared models.
- `:core:domain` contains Android-free business rules and depends only on model and common code.
- `:core:common` contains shared primitives such as clocks and coroutine dispatchers.
- `:core:testing` contains reusable test fakes and fixtures.
- `:build-logic` owns convention plugins and executable module-boundary checks.

Add shared behavior to the narrowest suitable `:core` module. Do not bypass a boundary by moving
implementation into `:app`; `./gradlew checkModuleBoundaries` enforces the full rules.

## Testing

The goal is that writing a good test is the easiest thing you can do. If a test is hard to write,
the design is usually the problem — say so in the pull request rather than working around it.

### The pyramid

| Layer | Where | What it covers | Budget |
|---|---|---|---|
| Unit | `src/test` in any module, plain JVM | business rules, state machines, mappers, view models | the bulk of the suite; milliseconds each |
| Integration | `src/test` at a module boundary | a real Room database in memory, a real serializer, a fake network or object store | one per boundary that can break |
| Instrumented / E2E | `src/androidTest`, Gradle Managed Devices | a few complete user journeys, and behavior only the real platform exhibits | a handful; nightly |

Push every test down as far as it will go. A rule that can be tested on the JVM must not be tested
on an emulator, and a test that needs the real network, the real clock or the real filesystem is a
test that needs a fake instead.

### Shared test infrastructure

`:core:testing` is on the test classpath of every module and holds the shared machinery:

- `FakeDevice` — a controllable wall clock, monotonic clock, boot id and time zone, so reboots,
  NTP corrections, deep sleep and DST are three-line tests rather than manual experiments.
- `TestDispatcherProvider` and `MainDispatcherExtension` — every injected dispatcher backed by one
  virtual clock, so `runTest` skips delays instead of sleeping and `Dispatchers.Main` works.
- `RecordingAppLogger` and `RecordingCrashReporter` — fakes for the logging and crash boundaries,
  so privacy promises such as "never log a file name" can be asserted.
- Data builders (`testStudySession()`, `testMaterial()`, …) — valid models by default, so a test
  names only the field it is about.
- `@Flaky` — the quarantine marker described below.

When you add a repository or a platform façade, add its fake to `:core:testing` in the same pull
request as the interface. `:core:testing` is a plain JVM module because `:core:domain` depends on
it and must stay Android-free; Android-only helpers belong beside the Android code they support.

Use Turbine (`api`-exposed from `:core:testing`) for flows, and Robolectric for logic that needs a
real platform implementation rather than a stub. Robolectric is a JUnit 4 runner, so annotate such
tests with `@RunWith(RobolectricTestRunner::class)`; the vintage engine runs them alongside the
Jupiter tests.

### Fast suite and slow suite

```bash
./gradlew test                              # inner loop: JVM unit tests, no emulator
./gradlew pixel6Api34DebugAndroidTest       # slow: instrumented tests on the managed device
./gradlew test -Pstudyflow.quarantine=true  # slow: only the quarantined tests
```

`./gradlew build` (what CI runs on every pull request) includes the fast suite. The emulator and
quarantine runs happen nightly in the `Slow verification` workflow. Keep it that way: nothing that
needs a device or minutes of wall clock belongs in the inner loop.

### Flaky tests are quarantined, never ignored

A test that fails intermittently is worse than no test, because it teaches everyone to ignore a red
build. When you find one:

1. Open an issue describing the flake.
2. Annotate the test `@Flaky(issue = "#123", reason = "…")`.
3. Fix it, or delete it deliberately with the reasoning in the issue.

The annotation tags the test `flaky`; `./gradlew test` excludes that tag and the nightly quarantine
run includes only it, so the flake stays measured. Do not use `@Disabled` and do not quietly delete
a failing test — both lose the coverage and the reason at the same time. An empty quarantine is the
goal, and a quarantined test with no progress on its issue should be fixed or removed.

### Coverage

`./gradlew check` writes JaCoCo reports for every module: JVM modules under
`<module>/build/reports/jacoco/` and Android modules under `<module>/build/reports/coverage/`. CI
reads both and annotates each pull request with the coverage of the lines it changed.

The only enforced number is a floor of 70% on the lines a pull request changed, which exists to
catch code that arrived with no test at all. Everything else is a signal: there is no
repository-wide percentage to defend, no ratchet on existing code, and a line covered by a test
that asserts nothing is worth less than an uncovered line whose risk you have thought about. Use
the report to notice what nobody exercised and then argue, in the pull request, about whether that
matters — including when the floor is the wrong answer for a particular change.

## Quality gates

`./gradlew check` is the single entry point and runs everything CI runs:

- **Spotless/ktlint** formatting, configured from `.editorconfig`. Run `./gradlew spotlessApply` to
  fix formatting instead of hand-editing whitespace.
- **detekt**, using the project ruleset in `config/detekt/detekt.yml` plus the Compose rules on
  modules that compile Compose code.
- **Android Lint** with `warningsAsErrors`, so a new warning fails the build.
- **Module boundary and module graph** checks.

Enable the opt-in formatting hint before your first commit if you want it; CI remains the real
gate, so nothing breaks if you do not:

```bash
git config core.hooksPath config/git-hooks
```

The hook only reports formatting; `git commit --no-verify` skips it for one commit.

### Baselines

A baseline records findings that already existed when a gate was introduced, so the gate can be
turned on without an unrelated cleanup in the same pull request. The only checked-in baseline is
`app/lint-baseline.xml`, which holds the missing launcher icon of the application skeleton.

Baselines are never a place to hide a new finding:

- A baseline is read only when the file is checked in; the build never generates one silently.
- Fix new findings. Do not run `./gradlew updateLintBaseline` to make a fresh failure disappear.
- Removing an entry from a baseline, by fixing the underlying issue, is always welcome; adding one
  needs a reason in the pull request description.

## Coding conventions

- Follow `.editorconfig`; run `./gradlew spotlessApply` to apply Kotlin formatting.
- Keep dependency and plugin versions in `gradle/libs.versions.toml`.
- Apply convention plugins rather than copying build configuration between modules.
- Keep public APIs explicit and compiler-warning free.
- Write unit tests for business rules and UI or screenshot tests for user-visible behavior where
  applicable.
- Record non-obvious, durable decisions in `docs/adr/`. Copy
  [`docs/adr/template.md`](docs/adr/template.md), assign the next number, and submit it with the
  implementation.

## Branches and commits

Create a short-lived branch from `main`, named for its purpose, such as `feature/timer-history`,
`fix/reboot-recovery`, or `docs/contributing`.

Keep commits focused and write imperative subjects, for example `Add recurrence boundary tests`.
Do not merge `main` into a feature branch solely to update it; rebase before review when needed.
Pull requests are squash-merged so `main` retains linear history. Never force-push after review has
started without coordinating with reviewers.

Open a pull request early, link its issue, complete the template, and request review from the
CODEOWNERS. Every pull request—including automated dependency updates—must pass the required
`Build and verify` CI check before merge.

## Definition of done

- Code and unit tests are complete.
- UI and screenshot tests are included where applicable.
- Lint, detekt, formatting, and module-boundary checks pass.
- Documentation and ADRs are updated where applicable.
- Accessibility has been checked where applicable.
- No new StrictMode, ANR, or memory-leak regressions were introduced.
- CI is green.
