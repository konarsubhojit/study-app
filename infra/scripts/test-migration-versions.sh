#!/usr/bin/env bash
set -euo pipefail

checker="$(dirname "$0")/check-migration-versions.sh"
directory="$(mktemp -d)"
trap 'rm -r "$directory"' EXIT
touch "$directory/20260927130000_first.sql" "$directory/20260927130100_second.sql"
bash "$checker" "$directory"
touch "$directory/20260927130000_duplicate.sql"
if output="$(bash "$checker" "$directory" 2>&1)"; then
  echo "Expected duplicate migration versions to fail" >&2
  exit 1
fi
[[ "$output" == *"20260927130000_first.sql"* && "$output" == *"20260927130000_duplicate.sql"* ]]
