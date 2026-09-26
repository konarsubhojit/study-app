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

## Releases

`app/version.properties` contains the next `major.minor.patch` version. A release is created only by
pushing the matching `vMAJOR.MINOR.PATCH` tag; the release workflow rejects any mismatch, derives
`versionCode` from the monotonic GitHub Actions run number, and records the tag, commit and versions
beside checksummed APK/AAB artifacts.

GitHub generates the changelog from merged pull-request labels using `.github/release.yml`. Apply
`feature`/`enhancement`, `bug`/`fix`, `infra`/`dependencies`, or `skip-changelog` as appropriate.
The same generated entry is truncated to Play's 500-character limit and attached to the release as
`whatsnew-en-US`.

Local builds derive a reproducible version code from `major * 1,000,000 + minor * 1,000 + patch`.
Never publish a local artifact: the release workflow overrides that fallback with its monotonic run
number.
The workflow signs both artifacts from the `STUDYFLOW_RELEASE_KEYSTORE_BASE64`,
`STUDYFLOW_RELEASE_STORE_PASSWORD`, `STUDYFLOW_RELEASE_KEY_ALIAS`, and
`STUDYFLOW_RELEASE_KEY_PASSWORD` repository secrets and verifies their signatures before upload.

Release APKs are inspected by `infra/scripts/verify-release-artifact.sh`; the workflow fails if
LeakCanary, Compose inspection, overlay, or sample-data seeder classes are present.

## Minification and keep rules

`productionRelease` (and every tagged release) builds with `isMinifyEnabled = true` and
`isShrinkResources = true`; R8 renames, inlines and removes anything it cannot prove is reachable.
Reflection — `Class.forName`, `ServiceLoader`, Room/Hilt/kotlinx.serialization codegen, Gradle's
own `androidx.startup.Initializer` and Glance's app-widget lookup — is invisible to that analysis,
so a missing keep rule does not fail the build. It fails at runtime, on whichever device reaches
that code path first, with an `UninitializedPropertyAccessException`, `ClassNotFoundException` or
`NoSuchFieldError`. `app/proguard-rules.pro` exists to make those failures build-time instead.

If you ship a new reflective dependency — anything resolved by class name, string, service-loader
entry or generated codegen rather than a direct reference — add its keep rules to
`app/proguard-rules.pro` (or the owning module's `consumerProguardFiles`, see below) **in the same
pull request**. Do not rely on someone hitting the crash later.

### Diagnosing a release-only crash

1. Build with shrinking on and check `app/build/outputs/mapping/productionRelease/missing_rules.txt`.
   AGP writes this file with R8's own suggested rules whenever it can prove a rule is missing; most
   reflection is invisible to that analysis, so an empty file does not mean the build is safe.
2. Reproduce with shrinking off to confirm R8 is actually the cause before writing any rule:
   `./gradlew :app:assembleProductionRelease -Pstudyflow.minifyRelease=false`. If the crash
   disappears, it is a keep-rule gap; if it persists, it is a regular bug.
3. Narrow the rule to the smallest scope that fixes it — prefer `-keepclassmembers` (keeps only the
   members R8 would otherwise strip or rename) over a blanket `-keep class ... { *; }`, which also
   disables shrinking and obfuscation for the whole hierarchy. Comment the rule with which library
   needs it and why, matching the existing blocks in `app/proguard-rules.pro`.
4. Confirm against the real thing: `./gradlew :app:installProductionRelease` and exercise the
   affected path while reading `adb logcat`. Only that install is both shrunk and obfuscated the
   way a shipped build is; `pixel6Api34ProductionReleaseTestAndroidTest` (see
   [Testing](#minified-release-smoke-test) below) is a useful fast regression check for a rule a
   *shrinking* gap needs, but it cannot confirm a rule that only a *renaming* gap needs, since its
   build type has to be debuggable to be instrumented at all, and AGP never obfuscates a debuggable
   build.

### Library-owned rules vs. `app/proguard-rules.pro`

Keep a rule in `app/proguard-rules.pro` when it exists only because of how `:app` wires a library
together (for example, Ktor's engine/plugin service loading, which is selected in
`app/di/NetworkModule.kt`, or a manifest-only component such as `AppStartupInitializer`). Prefer a
module's own `consumerProguardFiles` when the module itself owns the reflective type, so the rule
travels with the type instead of being rediscovered every time `:app`'s dependency graph changes.
Several dependencies already ship their own consumer rules this way — Room keeps its generated
`_Impl` classes, Hilt keeps `@EntryPoint` and `@HiltWorker` classes, and WorkManager keeps
`ListenableWorker` subclasses — which is why `app/proguard-rules.pro` does not repeat them.

## Module layout

- `:app` wires Android application concerns together.
- `:feature:*` owns a user-facing vertical slice and may depend on `:core:*`, never another feature.
- `:core:designsystem` owns the Material 3 theme and every design token; see
  [the design system guide](docs/design-system.md).
- `:core:model` contains dependency-free shared models.
- `:core:domain` contains Android-free business rules and depends only on model and common code.
- `:core:common` contains shared primitives such as clocks and coroutine dispatchers.
- `:core:database` owns Room entities, DAOs, migrations and domain mappings; exported schemas are
  reviewed source files.
- `:core:network` owns the API contract, the typed client, and its error model.
- `:core:testing` contains reusable test fakes and fixtures, including `FakeStudyFlowBackend`.
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

### Changing the Room schema

Never edit `core/database/schemas/*.json` by hand and never use destructive migration fallback to
hide a missing migration.

1. Change the entities and increment `StudyFlowDatabase.VERSION`.
2. Add each adjacent migration to `DatabaseMigrations.ALL`.
3. Run `./gradlew :core:database:testDebugUnitTest` with JDK 21. KSP exports the new schema and
   `MigrationTestHelper` validates migration into it.
4. Commit every generated schema version and update `DatabaseMigrationTest` for data semantics, not
   only structural validation.

The migration-registry test requires one path for every version from 1 through the current version.
Destructive fallback is development-only and is rejected at runtime when the APK is not debuggable.

### Fast suite and slow suite

```bash
./gradlew test                              # inner loop: JVM unit tests, no emulator
./gradlew pixel6Api34DebugAndroidTest       # slow: instrumented tests on the managed device
./gradlew test -Pstudyflow.quarantine=true  # slow: only the quarantined tests
```

`./gradlew build` (what CI runs on every pull request) includes the fast suite. The emulator and
quarantine runs happen nightly in the `Slow verification` workflow. Keep it that way: nothing that
needs a device or minutes of wall clock belongs in the inner loop.

### Minified release smoke test

`pixel6Api34DebugAndroidTest` runs against the debug build, which is never minified, so it cannot
catch a missing R8 keep rule. `pixel6Api34ProductionReleaseTestAndroidTest` runs the same
instrumented tests — including `ProductionReleaseSmokeInstrumentedTest` — against the
`productionReleaseTest` build type: `isMinifyEnabled`/`isShrinkResources` copied from `release`, but
`isDebuggable = true` so the instrumentation runner can attach to it. That smoke test launches the
app, reads settings from DataStore, and navigates to the task list, history and timer, then scans
logcat for the exception types a keep-rule gap produces.

This catches a keep-rule gap that only shrinking exposes — a reflectively-used class or member R8
removed entirely, which throws `ClassNotFoundException`/`NoSuchMethodError`. It does **not** catch a
gap that only *renaming* exposes, such as the original `Field theme_ for ea6 not found` crash:
`isDebuggable = true` makes AGP skip obfuscation outright, on every build type, so nothing under
`productionReleaseTest` is ever renamed (compare `app/build/outputs/mapping/productionReleaseTest/`
against `.../productionRelease/` — the same class keeps its own name in one and is `a1`-style
renamed in the other). This is a platform restriction, not a configuration bug: `am instrument`
refuses to attach to a non-debuggable target at all, so no on-device automated test can exercise
real obfuscation. The one check that does is the manual one in the pull request template — install
the actual `productionRelease` APK and read `adb logcat` — and it stays required for that reason.
It complements, and does not replace, the Robolectric tests in this suite: Robolectric never runs
R8 at all, so it cannot see even a shrinking-only gap.

Both managed-device tasks run nightly in `Slow verification`; run either locally the same way you
would `pixel6Api34DebugAndroidTest`.

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

`./gradlew check` writes JaCoCo reports for every module with unit tests: JVM modules under
`<module>/build/reports/jacoco/` and Android modules under `<module>/build/reports/coverage/`. CI
reads both and annotates each pull request with the coverage of the files it changed.

The only enforced number is a floor of 70% on the files a pull request changed, which exists to
catch code that arrived with no test at all. Everything else is a signal: there is no
repository-wide percentage to defend, no ratchet on existing code, and a line covered by a test
that asserts nothing is worth less than an uncovered line whose risk you have thought about. Use
the report to notice what nobody exercised and then argue, in the pull request, about whether that
matters — including when the floor is the wrong answer for a particular change.

## Changing the API

[`docs/api/openapi.yaml`](docs/api/openapi.yaml) is the source of truth for the app/backend
contract, and it carries the versioning and deprecation policy. Start there: add or change the
operation in the specification, then add the matching `ApiEndpoint` entry and DTOs in
`:core:network`. `OpenApiContractTest` fails the build when the client calls something the
specification does not describe.

Responses ignore unknown fields on purpose, so a server may add a field at any time; do not
"tighten" the JSON configuration. Map every new failure to a `UserFacingMessage` — an HTTP status
code must never reach the UI. See [ADR 0007](docs/adr/0007-api-contract-and-network-client.md).

Run against a local mock backend without changing any code:

```bash
./gradlew installDebug -Pstudyflow.apiBaseUrl=http://10.0.2.2:8080
```

## Quality gates

`./gradlew check` is the single entry point and runs everything CI runs:

- **Spotless/ktlint** formatting, configured from `.editorconfig`. Run `./gradlew spotlessApply` to
  fix formatting instead of hand-editing whitespace.
- **detekt**, using the project ruleset in `config/detekt/detekt.yml` plus the Compose rules on
  modules that compile Compose code.
- **Android Lint** with `warningsAsErrors`, so a new warning fails the build.
- **Compose preview screenshot tests** on modules that apply `studyflow.screenshot`. Re-record them
  with `./gradlew :core:ui:updateDebugScreenshotTest` (or the affected module) only when the image
  change is the change you meant to make, and say so in the pull request.
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
