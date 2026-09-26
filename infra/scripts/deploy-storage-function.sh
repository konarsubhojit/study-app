#!/usr/bin/env bash
# Sets the storage Edge Function's secrets and deploys it to a Supabase project.
#
# SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by the hosted Edge Function runtime.
#
# Usage: ./deploy-storage-function.sh <project-ref>
set -euo pipefail
cd "$(dirname "$0")/.."

project_ref="${1:?usage: deploy-storage-function.sh <project-ref>}"

: "${SUPABASE_ACCESS_TOKEN:?SUPABASE_ACCESS_TOKEN must be set (from a managed secret store)}"
: "${STORAGE_S3_ENDPOINT:?STORAGE_S3_ENDPOINT must be set (from a managed secret store)}"
: "${STORAGE_S3_BUCKET:?STORAGE_S3_BUCKET must be set (from a managed secret store)}"
: "${STORAGE_S3_ACCESS_KEY_ID:?STORAGE_S3_ACCESS_KEY_ID must be set (from a managed secret store)}"
: "${STORAGE_S3_SECRET_ACCESS_KEY:?STORAGE_S3_SECRET_ACCESS_KEY must be set (from a managed secret store)}"
: "${ORPHAN_REAPER_TOKEN:?ORPHAN_REAPER_TOKEN must be set (from a managed secret store)}"

function_secrets=(
    "STORAGE_S3_ENDPOINT=$STORAGE_S3_ENDPOINT"
    "STORAGE_S3_BUCKET=$STORAGE_S3_BUCKET"
    "STORAGE_S3_ACCESS_KEY_ID=$STORAGE_S3_ACCESS_KEY_ID"
    "STORAGE_S3_SECRET_ACCESS_KEY=$STORAGE_S3_SECRET_ACCESS_KEY"
    "ORPHAN_REAPER_TOKEN=$ORPHAN_REAPER_TOKEN"
)

[[ -z "${STORAGE_S3_REGION:-}" ]] || function_secrets+=("STORAGE_S3_REGION=$STORAGE_S3_REGION")
[[ -z "${STORAGE_SCAN_HOOK_URL:-}" ]] || function_secrets+=("STORAGE_SCAN_HOOK_URL=$STORAGE_SCAN_HOOK_URL")
[[ -z "${STORAGE_SCAN_HOOK_TOKEN:-}" ]] || function_secrets+=("STORAGE_SCAN_HOOK_TOKEN=$STORAGE_SCAN_HOOK_TOKEN")

supabase link --project-ref "$project_ref"
supabase secrets set "${function_secrets[@]}"
supabase functions deploy storage
