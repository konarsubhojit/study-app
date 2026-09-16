# 7. Tests are a pyramid with shared fakes, and coverage is a signal

- Status: accepted
- Date: 2026-09-16

## Context

An app whose headline features are "a timer that survives a reboot" and "a reminder that fires at
the right local time in October" cannot be verified by clicking through it. Both behaviours depend
on conditions no reviewer can reproduce on demand: a device restart mid-session, an NTP correction,
a DST boundary, a revoked exact-alarm permission, a failed upload resumed on a flaky connection.

Left to itself, a project in this position drifts one of two ways. Either it grows a large suite of
instrumented tests that need an emulator, take twenty minutes and fail for reasons unrelated to the
change — so the team stops reading them — or it tests almost nothing and relies on manual passes
before each release.

The forces:

- A contributor will write the test that is easiest to write. Whatever the infrastructure makes
  cheap is what the suite will consist of.
- Determinism is a property of the *dependencies*, not of the assertions. Real clocks, real
  schedulers, real networks and real filesystems are where flakes come from.
- Feedback speed matters more than breadth in the inner loop, and breadth matters more than speed
  overnight.
- Coverage percentages are trivially gamed and, used as a target, reward asserting nothing at all.

## Decision

**A pyramid, documented in `CONTRIBUTING.md`.** Many fast JVM unit tests over pure logic; focused
integration tests at module boundaries (a real Room database in memory, a real serializer, a fake
network); a handful of end-to-end journeys on a managed device.

**Every platform dependency is an injected interface with a fake in `:core:testing`.** The clock is
already `WallClock`/`UptimeClock`/`BootIdProvider` with `FakeDevice` behind them; dispatchers are
`DispatcherProvider` with `TestDispatcherProvider`; logging and crash reporting are interfaces with
recording fakes. New platform façades — scheduler, object store, notifications — ship with their
fake in the same change as the interface, not afterwards. Data builders keep a fixture down to the
one field the test is about.

**`:core:testing` stays a plain JVM module.** It is depended on by `:core:domain`, which must not
see the Android SDK. Android-only helpers therefore live next to the Android code they support: a
Hilt test runner belongs to the module that first has a Hilt graph to replace, and an in-memory Room
rule belongs to `:core:database` once it has entities. Adding either before then would be untestable
scaffolding pinned to a schema nobody has written.

**Robolectric for Android-dependent logic, Gradle Managed Devices for the rest.** Robolectric runs
in the JVM suite; it is a JUnit 4 runner, so the vintage engine executes it on the same JUnit
Platform as the Jupiter tests. Instrumented runs use a pinned managed device (`pixel6Api34`,
an ATD image) so the device is identical everywhere and is created and destroyed by the build.

**A fast suite and a slow suite, with an explicit quarantine.** `./gradlew test` is the inner loop
and the pull-request gate. Emulator runs and the quarantine are nightly. A test known to flake is
annotated `@Flaky(issue = "#123")`, which tags it `flaky`; the default suite excludes that tag and
the nightly run includes only it. `@Disabled` and deletion are not substitutes: both lose the
coverage *and* the memory of why.

**Coverage is reported, gated on new code, and never a repository-wide target.** JaCoCo XML is
produced by `check`; CI annotates a pull request with the coverage of the files it changed and
fails below a 70% floor on those files. The floor is there to catch code that arrived with no test
at all, not to be optimised: there is no overall percentage to defend and no ratchet on existing
code.

## Consequences

- A feature can be tested with no network, no real clock and no filesystem, which is what makes the
  timer's reboot and clock-change behaviour testable at all.
- The inner loop stays fast because nothing slow is allowed into `./gradlew test`.
- A flake is visible and owned instead of being silently re-run. The cost is that a quarantined
  test protects nothing until it is fixed, so quarantine needs a tracking issue by construction.
- Coverage on changed files is a floor rather than a verdict. A change that trips it and should
  not have is a conversation in the pull request — the number is evidence in that conversation.
- Fakes are code that must be maintained alongside the interfaces they stand in for, and a fake
  that drifts from the real implementation is a false sense of safety — which is why contract-level
  integration tests at the module boundary remain part of the pyramid rather than optional.
