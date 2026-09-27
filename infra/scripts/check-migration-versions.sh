#!/usr/bin/env bash
set -euo pipefail

migrations_dir="${1:-$(dirname "$0")/../supabase/migrations}"
declare -A seen=()

for file in "$migrations_dir"/*.sql; do
  [[ -e "$file" ]] || continue
  name="${file##*/}"
  version="${name%%_*}"
  if [[ -n "${seen[$version]:-}" ]]; then
    printf 'Duplicate migration version %s: %s and %s\n' "$version" "${seen[$version]}" "$file" >&2
    exit 1
  fi
  seen[$version]="$file"
done
