#!/usr/bin/env bash
# Stops the local Supabase stack.
set -euo pipefail
cd "$(dirname "$0")/.."

supabase stop
