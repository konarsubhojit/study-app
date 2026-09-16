# 2. Architecture layering and module boundaries

- Status: accepted
- Date: 2026-09-16

## Context

StudyFlow has three substantial, independent feature areas (timer, materials, tasks/reminders) and
a lot of genuinely tricky logic that has nothing to do with Android: measuring elapsed time across
reboots, expanding recurrence rules across DST boundaries, planning resumable uploads, deciding what
is safe to extract from an untrusted archive.

That logic is only testable in seconds — rather than minutes on an emulator — if it never touches
the Android SDK. Architecture documents that merely *ask* for that never survive contact with a
deadline.

## Decision

**Unidirectional layering:** Compose UI → ViewModel (UDF/MVI) → pure-Kotlin domain → data.
Dependencies point inwards only. Room is the single source of truth for local state; network,
object storage and system schedulers sit behind interfaces owned by the domain.

**Module map:**

```
:app
  └── :feature:*          timer, materials, tasks, dashboard, settings
        └── :core:ui, :core:designsystem
        └── :core:domain
              └── :core:model, :core:common
        └── :core:database, :core:datastore, :core:network,
            :core:storage, :core:notifications, :core:scheduling
:build-logic               convention plugins (included build)
:benchmark                 macrobenchmark + baseline profiles
```

**`:core:model` and `:core:domain` are plain `kotlin("jvm")` modules.** This is the part that
actually enforces "Android-free": the Android SDK is simply not on the compile classpath, so an
accidental `import android.*` is a compile error rather than a code-review comment. It also leaves
the door open for a Kotlin Multiplatform target later without a rewrite.

**The rules are executable.** `studyflow.module-boundaries` inspects the project dependency graph
and fails `check` with a specific message:

| Rule | Rationale |
|---|---|
| `:core:model` may depend on nothing | it is the leaf every other layer shares |
| `:core:domain` may depend only on `:core:model` and `:core:common` | keeps the domain portable |
| `:feature:*` may not depend on `:feature:*` | forces shared code down into `:core` |
| `:core:*` may not depend on `:feature:*` or `:app` | keeps the arrows pointing one way |

Test source sets are checked more loosely — any module may use `:core:testing` — but cross-feature
edges are forbidden there too.

## Consequences

- The domain test suite runs on the JVM in seconds, which is why it is worth writing exhaustive
  tests for reboots and DST transitions.
- A violated boundary fails CI with an explanation, not a review comment three days later.
- There is more ceremony up front: adding a feature means touching `settings.gradle.kts` and
  creating a module rather than adding a package.
- Sharing code between two features requires deciding where in `:core` it belongs. That is the
  intended friction; the alternative is a dependency web nobody can untangle later.
