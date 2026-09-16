# 7. Backend platform: Supabase, environments and the row-owns-itself data model

- Status: accepted
- Date: 2026-09-16

## Context

Epic 6 (#7) needs a real backend behind the client contract already fixed by [ADR 0005](0005-object-storage.md):
the app never holds cloud credentials, auth and storage are behind a thin BFF, and everything works
offline first. That ADR proposed Supabase but left the decision open pending a proper comparison, and
left environments, schema and authorisation unspecified. This ADR closes those gaps.

Three options were on the table, unchanged from ADR 0005:

1. **Supabase** — hosted Postgres, Auth (including passkeys via WebAuthn), Storage with an
   S3-compatible API, Row Level Security, Edge Functions.
2. **Custom Ktor + Postgres + S3-compatible store (R2/MinIO)** — a backend we write and operate
   ourselves, fronting a plain Postgres and an object store of our choosing.
3. **Local-only v1** — no backend at all; every device is an island.

## Decision

### Scoring

Each criterion scored 1 (worst) to 5 (best) for a solo/small-team project pre-revenue.

| Criterion | Supabase | Ktor + Postgres + S3 | Local-only |
|---|---|---|---|
| Time-to-first-value | 5 — auth, DB, storage and RLS exist day one | 2 — every one of those is a service to build | 5 — nothing to stand up, but see exit cost |
| Auth quality (passkeys) | 4 — WebAuthn/passkeys and Sign in with Google are built-in GoTrue flows | 2 — passkeys are a from-scratch WebAuthn ceremony implementation | 1 — no account concept, no cross-device identity |
| Storage/egress cost | 3 — S3-compatible, but egress and storage are billed at Supabase's rates | 4 — R2 has zero egress fees; MinIO self-hosted is cost-controlled but ops-heavy | 5 — zero cloud cost |
| Operational burden | 4 — managed Postgres, backups, connection pooling and monitoring included | 1 — we own uptime, patching, backups, scaling and on-call | 5 — nothing to operate |
| Data portability | 4 — plain Postgres dump/restore; storage objects fetchable over the S3 API | 5 — we already own every format and API surface | 3 — device backups only, no server-side format at all |
| Exit cost | 4 — Postgres and S3-compatible storage are both portable formats; RLS policies are just SQL | 5 — nothing to migrate away from, it is already bespoke | 2 — "exit" means building the entire sync backend from zero, motivated by user demand rather than a calm ADR |
| **Total** | **24** | **19** | **21** |

Local-only scores well on paper because it has nothing to point at, but that number is misleading:
it fails the epic's actual exit criterion outright ("sign in on a second device and all completed
sessions... appear"), so it is not a substitute for a backend, only a valid *mode* the backend must
keep working in (already the case per ADR 0005 and the app's offline-first design).

Between the two real backends, the Ktor option loses almost entirely on time-to-first-value and
operational burden — for a project at this stage, building and running our own auth service is
weeks of work to reach parity with a `supabase start`, and passkeys specifically are a security-
sensitive protocol not worth reimplementing to save on hosting fees we can address later with
storage lifecycle rules (already scoped in epic 7).

**Decision: Supabase**, confirming the ADR 0005 recommendation. The client contract from ADR 0005
(no cloud credentials in the app, presigned URLs, content-addressed uploads) is unchanged and is
exactly what makes this a reversible decision: everything downstream of "Postgres + an S3-compatible
bucket" is provider-agnostic, so the exit path (below) is a data-layer migration, not a rewrite.

### Environments

The Supabase CLI is the infrastructure-as-code tool: a project's entire schema — extensions,
tables, triggers, RLS policies — is a sequence of numbered SQL migrations under
[`infra/supabase/migrations`](../../infra/supabase/migrations), applied by `supabase db reset`
(local) or `supabase db push` (linked project). There is no hand-run SQL against any environment;
the migration files *are* the source of truth, checked into the repository like any other code.

- **Dev**: `supabase start` boots a full local stack (Postgres, GoTrue, PostgREST, Storage, Realtime,
  Studio) in Docker from the migrations, byte-for-byte the same schema prod will get. Anyone can
  destroy and recreate it (`supabase stop --no-backup && supabase start`) in minutes, with no
  cloud account and no secret required.
- **Prod**: a Supabase project linked with `supabase link`, updated with `supabase db push`. The
  project's access token and database password are read from environment variables the CI runner
  or operator's shell provides at the moment of use — a managed secrets store (e.g. GitHub Actions
  encrypted secrets, or a password manager for a human operator) — and are never written to disk in
  this repository. See [`infra/README.md`](../../infra/README.md) for the exact commands.

### Data model

Every table (`infra/supabase/migrations/20260916090100`–`20260916090600`) that stores user data
carries a `user_id` foreign key to `profiles` (itself keyed to `auth.users`) and an owner-scoped
Row Level Security policy for `select`/`insert`/`update`/`delete`: `auth.uid() = user_id`. There is
no code path, cached view or default-open table anywhere in the schema that would let one
authenticated user's query return another user's row — Postgres enforces it below the API, so a bug
in the client or the auto-generated REST layer cannot leak data:

| Table | Purpose |
|---|---|
| `profiles` | one row per `auth.users` row; every other table's `user_id` points here |
| `subjects` | subjects/courses, mirrors `core.model.Subject` |
| `study_sessions` | **completed** sessions only (epic 7: the running timer is device-scoped) |
| `study_tasks`, `reminders` | to-dos and their reminder rules, mirrors `core.model.StudyTask`/`Reminder` |
| `material_folders`, `materials` | the materials catalogue; `materials.storage_key` is constrained to `<user_id>/<content_hash>`, so object-key ownership is enforced by the same row a user does or doesn't own |
| `sync_cursors` | one row per `(user, device, entity type)`, the delta-pull watermark |

`updated_at` (server-stamped, never client-supplied) plus `device_id` gives the last-write-wins
conflict rule epic 7 specifies; `deleted_at` is a tombstone column so a delete on one device is
something a peer device's delta pull can observe, rather than a row simply vanishing.

### Authorisation tests

[`infra/supabase/tests/database/authorization.sql`](../../infra/supabase/tests/database/authorization.sql)
is a pgTAP suite that creates two users, seeds one row per table for the first, and then asserts,
acting as the second user, that every `select` returns zero rows and every `update`/`delete`
affects zero rows — proving cross-user access is impossible at the data layer rather than merely
untested. It runs with `supabase test db --local` against the real migrations, so it exercises the
actual RLS policies prod will run, not a hand-written approximation of them.

## Consequences

- Auth, Postgres and object storage exist without a line of server code written, and passkeys work
  on day one instead of being a bespoke WebAuthn project.
- The entire authorisation model is auditable as SQL: `grep "for select" infra/supabase/migrations`
  shows every ownership rule in the system in one screen.
- Cost is Supabase's hosted pricing rather than raw infrastructure cost; if usage or pricing makes
  that unattractive, the exit path is: `pg_dump` the Postgres database, copy objects out of Storage
  over its S3-compatible API, and point the same schema and RLS policies at a self-hosted Postgres
  and an S3-compatible store (R2/MinIO) fronted by a small Ktor BFF — a data-layer migration the
  client never has to know happened, because it already only talks to a BFF over the API contract
  from ADR 0005.
- Environments are disposable: a contributor with Docker and no cloud credentials can stand up the
  full stack, apply every migration and run the authorisation suite locally.
