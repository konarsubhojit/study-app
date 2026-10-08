#!/usr/bin/env bash
# Runs the storage function's real-stack test (functions/storage/stack_test.ts) against the local
# Supabase stack, which must already be running: `supabase start` from infra/.
#
# The function runs in-process under Deno on the host and talks to the stack's real Storage S3
# protocol, so every request the provider sees is the one production sends (ADR 0016).
set -euo pipefail
cd "$(dirname "$0")/.."

status="$(supabase status -o env)"
value() {
  local line
  line="$(grep -E "^$1=" <<<"$status" | head -n1)"
  [[ -n "$line" ]] || { printf 'supabase status did not report %s; is the stack running?\n' "$1" >&2; exit 1; }
  line="${line#*=}"
  line="${line#\"}"
  printf '%s' "${line%\"}"
}

STORAGE_STACK_TEST=1 \
SUPABASE_URL="$(value API_URL)" \
SUPABASE_SERVICE_ROLE_KEY="$(value SERVICE_ROLE_KEY)" \
SUPABASE_ANON_KEY="$(value ANON_KEY)" \
STORAGE_S3_BUCKET=materials \
STORAGE_S3_ENDPOINT="$(value STORAGE_S3_URL)" \
STORAGE_S3_REGION="$(value S3_PROTOCOL_REGION)" \
STORAGE_S3_ACCESS_KEY_ID="$(value S3_PROTOCOL_ACCESS_KEY_ID)" \
STORAGE_S3_SECRET_ACCESS_KEY="$(value S3_PROTOCOL_ACCESS_KEY_SECRET)" \
  deno test --allow-env --allow-net --allow-sys=osRelease supabase/functions/storage/stack_test.ts
