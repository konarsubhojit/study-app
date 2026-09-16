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
