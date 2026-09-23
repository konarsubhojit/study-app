#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?usage: verify-release-artifact.sh <release.apk>}"
[[ -f "$artifact" ]] || {
  echo "Release artifact not found: $artifact" >&2
  exit 1
}

# Add the DEX descriptor or unique class name of every debug-only tool introduced by the build.
readonly forbidden_classes=(
  "Lleakcanary/"
  "Lcom/squareup/leakcanary/"
  "Landroidx/compose/ui/tooling/ComposeViewAdapter;"
  "Ldev/studyflow/app/debug/SampleDataSeeder;"
  "Ldev/studyflow/app/debug/InspectionOverlay;"
)

unzip -tq "$artifact" >/dev/null
mapfile -t dex_files < <(unzip -Z1 "$artifact" | grep -E '^classes([0-9]+)?\.dex$')
((${#dex_files[@]} > 0)) || {
  echo "No DEX files found in $artifact" >&2
  exit 1
}

dex_strings="$(mktemp)"
trap 'rm -f "$dex_strings"' EXIT
for dex_file in "${dex_files[@]}"; do
  unzip -p "$artifact" "$dex_file" | strings >> "$dex_strings"
done

for forbidden_class in "${forbidden_classes[@]}"; do
  if grep -Fq "$forbidden_class" "$dex_strings"; then
    echo "Debug tooling '$forbidden_class' exists in $artifact" >&2
    exit 1
  fi
done

echo "Verified that $artifact contains no debug tooling classes."
