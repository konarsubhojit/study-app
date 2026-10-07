# 19. Group scheduling dependency injection by responsibility

- Status: accepted
- Date: 2026-10-07

## Context

`:core:scheduling` owns platform-backed scheduling and transfer adapters, but its Hilt bindings
were collected in one `SchedulingModule`. That module had 28 provider methods wiring reminders,
weekly summaries, material transfers, and session synchronization. These features have distinct
dependencies and change for different reasons, so the single module made routine changes require
reading and reviewing unrelated wiring together.

## Decision

Keep the bindings in `:core:scheduling`, beside the implementations they construct, and split them
into responsibility-focused Hilt modules:

- `SchedulingModule` wires reminder delivery, scheduling capabilities, the anchored clock, and
  remote reminder integrity checks.
- `WeeklySummaryModule` wires summary settings, scheduling, and delivery.
- `MaterialTransferModule` wires material upload and download persistence, transports, and
  coordinators.
- `SchedulingSyncModule` wires sync transport, execution, WorkManager scheduling, and the session
  command observer.

All modules install into the same `SingletonComponent`; this is an organizational boundary, not a
change to object scope or behavior. Bindings shared across responsibilities remain available from
the same component without adding module dependencies.

## Consequences

- A feature's construction graph is easier to find and review without changing its runtime wiring.
- Adding a new binding still requires placing it in the module that owns the responsibility; this
  does not split Gradle modules or change the project dependency graph.
- Future additions may justify further separation, but modules should remain cohesive rather than
  being split merely to satisfy a line-count target.
