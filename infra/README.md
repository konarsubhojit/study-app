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
were exercised locally and by the authorisation tests. The primary deployment path is the
manually-run **Deploy backend** GitHub Actions workflow; it works from a mobile browser and requires
no local machine, Docker, or Supabase CLI.

### GitHub Actions setup

Add these repository Actions secrets:

| Secret | Purpose | Where to get it |
| --- | --- | --- |
| `SUPABASE_ACCESS_TOKEN` | Authenticates the CLI to the Supabase account | Supabase dashboard → Account → Access Tokens |
| `SUPABASE_DB_PASSWORD` | The target project's database password | Supabase dashboard → Project → Settings → Database |
| `STORAGE_REAPER_URL` | Endpoint hit hourly by `reap-storage-orphans.yml` | Deployed storage Edge Function URL |
| `STORAGE_REAPER_TOKEN` | Must match `ORPHAN_REAPER_TOKEN` on the Edge Function | The managed secret chosen when deploying the function |

In GitHub, open **Repository → Settings → Secrets and variables → Actions → New repository
secret**, enter each name and value, then save it. `project_ref` is a workflow input, not a secret;
find it in the Supabase dashboard under **Project → Settings → General**. Adding secrets and running
the deploy both work from a mobile browser.

Storing `SUPABASE_ACCESS_TOKEN` and `SUPABASE_DB_PASSWORD` as repository Actions secrets is a
deliberate change from the previous credential guidance, which implied that credentials live only
in a password manager or external CI secret store. No credential, connection string, or project ref
is committed anywhere under `infra/`.

### Deploy with GitHub Actions

From the GitHub mobile or desktop web UI, open the **Actions** tab, select **Deploy backend**, choose
**Run workflow**, enter the project ref, and tap **Run workflow**. Any maintainer with write access
can trigger this deploy. `supabase db push` is not reversible; if approval rules are wanted later,
add them to the `production` environment.

### Deploy locally

```sh
export SUPABASE_ACCESS_TOKEN=...   # from a managed secret store — never committed
export SUPABASE_DB_PASSWORD=...    # ditto
./infra/scripts/deploy-prod.sh <project-ref>
```

`deploy-prod.sh` only ever reads these from the environment; nothing under `infra/` contains a
prod credential, connection string or project ref. `<project-ref>` identifies which Supabase project
to link and push to, and is not a secret by itself, but is still passed as an argument rather than
hard-coded so the script can never be run against the wrong project by accident.

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

## Presigned storage service

`supabase/functions/storage` is the only component that receives S3-compatible storage
credentials. Deploy it with `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `STORAGE_S3_ENDPOINT`,
`STORAGE_S3_REGION`, `STORAGE_S3_BUCKET`, `STORAGE_S3_ACCESS_KEY_ID`, and
`STORAGE_S3_SECRET_ACCESS_KEY`. Set `ORPHAN_REAPER_TOKEN` to a random managed secret, then configure
the repository's `STORAGE_REAPER_URL` and `STORAGE_REAPER_TOKEN` Actions secrets so
`reap-storage-orphans.yml` aborts abandoned multipart uploads hourly.

An optional malware scanner can be enabled with `STORAGE_SCAN_HOOK_URL` and
`STORAGE_SCAN_HOOK_TOKEN`. The hook must return `{"clean":true,"sha256":"<expected digest>"}`;
rejected objects are deleted before their metadata becomes downloadable.

## Operations

Backend dashboards, alerts, cost monitoring, abuse response and the backup/restore drill are tracked
in [`docs/backend-observability.md`](../docs/backend-observability.md). The database migrations expose
the dashboard views and alert/lifecycle policy rows; the Edge Function writes only sanitized request
telemetry and never logs presigned URLs, object keys or user identifiers.
