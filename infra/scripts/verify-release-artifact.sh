#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?usage: verify-release-artifact.sh <release.apk>}"
[[ -f "$artifact" ]] || {
  echo "Release artifact not found: $artifact" >&2
  exit 1
}

for forbidden_class in \
  "Lleakcanary/" \
  "Lcom/squareup/leakcanary/" \
  "Landroidx/compose/ui/tooling/ComposeViewAdapter;" \
  "SampleDataSeeder" \
  "InspectionOverlay"; do
  if unzip -p "$artifact" 'classes*.dex' | strings | grep -Fq "$forbidden_class"; then
    echo "Debug tooling '$forbidden_class' exists in $artifact" >&2
    exit 1
  fi
done

echo "Verified that $artifact contains no debug tooling classes."
