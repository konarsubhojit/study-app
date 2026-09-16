#!/usr/bin/env bash
# Links this repository's migrations to a Supabase project and pushes them.
#
# Required environment variables (read from a managed secrets store, never committed):
#   SUPABASE_ACCESS_TOKEN  - a Supabase personal/CI access token
#   SUPABASE_DB_PASSWORD   - the target project's database password
#
# Usage: ./deploy-prod.sh <project-ref>
set -euo pipefail
cd "$(dirname "$0")/.."

project_ref="${1:?usage: deploy-prod.sh <project-ref>}"

: "${SUPABASE_ACCESS_TOKEN:?SUPABASE_ACCESS_TOKEN must be set (from a managed secret store)}"
: "${SUPABASE_DB_PASSWORD:?SUPABASE_DB_PASSWORD must be set (from a managed secret store)}"

supabase link --project-ref "$project_ref"
supabase db push
