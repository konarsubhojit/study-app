#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?usage: verify-release-artifact.sh <release.apk>}"
[[ -f "$artifact" ]] || {
  echo "Release artifact not found: $artifact" >&2
  exit 1
}

mapfile -t dex_files < <(unzip -Z1 "$artifact" | grep -E '^classes([0-9]+)?\.dex$')
((${#dex_files[@]} > 0)) || {
  echo "No DEX files found in $artifact" >&2
  exit 1
}

for forbidden_class in \
  "Lleakcanary/" \
  "Lcom/squareup/leakcanary/" \
  "Landroidx/compose/ui/tooling/ComposeViewAdapter;" \
  "SampleDataSeeder" \
  "InspectionOverlay"; do
  for dex_file in "${dex_files[@]}"; do
    if unzip -p "$artifact" "$dex_file" | strings | grep -Fq "$forbidden_class"; then
      echo "Debug tooling '$forbidden_class' exists in $artifact ($dex_file)" >&2
      exit 1
    fi
  done
done

echo "Verified that $artifact contains no debug tooling classes."
