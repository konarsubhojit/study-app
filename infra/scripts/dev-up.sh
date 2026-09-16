#!/usr/bin/env bash
# Starts the local Supabase stack and applies every migration from scratch.
set -euo pipefail
cd "$(dirname "$0")/.."

if supabase status >/dev/null 2>&1; then
  echo "Stack already running; resetting the database to apply migrations from scratch..."
  supabase db reset
else
  supabase start
fi

supabase status
