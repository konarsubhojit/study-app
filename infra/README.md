# Infrastructure

Backend platform: [Supabase](https://supabase.com) (Postgres + Auth + Storage + Row Level
Security). See [ADR 0007](../docs/adr/0007-backend-platform.md) for why, the cost model and the
exit path, and [ADR 0005](../docs/adr/0005-object-storage.md) for the client upload contract this
backend serves.

Everything under [`supabase/`](supabase) is infrastructure-as-code: the schema, RLS policies and
authorisation tests are SQL files checked into this repository, applied the same way to every
environment. Nobody runs ad hoc SQL against dev or prod; a schema change is a new migration file in
a pull request.

```
infra/
  supabase/
    config.toml            # project settings (ports, auth providers, studio, etc.)
    migrations/            # numbered SQL migrations — the schema, in order, from scratch
    tests/database/         # pgTAP authorisation tests
  scripts/
    dev-up.sh               # start the full local stack
    dev-down.sh              # stop it
    test.sh                   # run the authorisation test suite locally
    deploy-prod.sh           # push migrations to the linked prod project
```

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) — the local stack (Postgres, Auth, Storage,
  Realtime, Studio) runs as containers.
- [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started) — install
  with `npm install -g supabase`, or any method on that page.

No Supabase account or cloud credential is needed for local development.

## Dev environment

Recreate the entire dev environment from scratch at any time:

```sh
cd infra
./scripts/dev-up.sh      # supabase start; applies every migration to a fresh Postgres
./scripts/test.sh        # supabase test db --local; runs the pgTAP authorisation suite
./scripts/dev-down.sh    # supabase stop; tears the stack down again
```

`dev-up.sh` always starts from a clean database (`supabase db reset` if the stack is already
running, `supabase start` otherwise), so "recreate from scratch" is a real, exercised code path, not
just a claim. Local credentials, URLs and ports are printed by `supabase status` after start; they
are locally-generated defaults, not secrets, and are never used against any other environment.

Studio (a local admin UI for browsing tables and running SQL) is available at
`http://127.0.0.1:54323` once the stack is up.

## Prod environment

Prod is a Supabase project linked to this repository, updated by pushing the same migrations that
were exercised locally and by the authorisation tests:

```sh
export SUPABASE_ACCESS_TOKEN=...   # from a managed secret store (CI secret, password manager) — never committed
export SUPABASE_DB_PASSWORD=...    # ditto
./infra/scripts/deploy-prod.sh <project-ref>
```

`deploy-prod.sh` only ever reads these from the environment; nothing under `infra/` contains a
prod credential, connection string or project ref. `<project-ref>` identifies which Supabase
project to link and push to, and is not a secret by itself, but is still passed as an argument
rather than hard-coded so the script can never be run against the wrong project by accident.

If Supabase were ever discontinued or became too expensive (see ADR 0007's cost model), the same
migrations apply as-is to any Postgres instance; only `deploy-prod.sh`'s use of `supabase db push`
against a linked *Supabase* project would need to change to a plain `psql` invocation against the
new host.

## Data model and authorisation

See [ADR 0007](../docs/adr/0007-backend-platform.md#data-model) for the table-by-table description.
Every user-owned table has an RLS policy tying every row to its owner
(`auth.uid() = user_id`); [`supabase/tests/database/authorization.sql`](supabase/tests/database/authorization.sql)
proves cross-user reads and writes affect zero rows for every table, run against the real
migrations rather than a hand-written approximation of them.
