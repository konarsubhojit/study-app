#!/usr/bin/env bash
# Sets Edge Function secrets and deploys one StudyFlow Supabase Edge Function.
#
# SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by the hosted Edge Function runtime.
#
# Pending repository migrations are pushed before any function is deployed. The functions call
# RPC signatures introduced by those migrations; deploying code ahead of its schema makes PostgREST
# answer PGRST202 (function not found) and every affected request fail as storage_unavailable.
#
# Usage: ./deploy-storage-function.sh <project-ref> [storage|api|all]
set -euo pipefail
cd "$(dirname "$0")/.."

project_ref="${1:?usage: deploy-storage-function.sh <project-ref> [storage|api|all]}"
function_name="${2:-storage}"

: "${SUPABASE_ACCESS_TOKEN:?SUPABASE_ACCESS_TOKEN must be set (from a managed secret store)}"
: "${SUPABASE_DB_PASSWORD:?SUPABASE_DB_PASSWORD must be set so pending migrations deploy before the function}"

storage_secrets=()
api_secrets=()

require_storage_secrets() {
    : "${STORAGE_S3_ENDPOINT:?STORAGE_S3_ENDPOINT must be set (from a managed secret store)}"
    : "${STORAGE_S3_BUCKET:?STORAGE_S3_BUCKET must be set (from a managed secret store)}"
    : "${STORAGE_S3_ACCESS_KEY_ID:?STORAGE_S3_ACCESS_KEY_ID must be set (from a managed secret store)}"
    : "${STORAGE_S3_SECRET_ACCESS_KEY:?STORAGE_S3_SECRET_ACCESS_KEY must be set (from a managed secret store)}"
    : "${ORPHAN_REAPER_TOKEN:?ORPHAN_REAPER_TOKEN must be set (from a managed secret store)}"

    storage_secrets=(
        "STORAGE_S3_ENDPOINT=$STORAGE_S3_ENDPOINT"
        "STORAGE_S3_BUCKET=$STORAGE_S3_BUCKET"
        "STORAGE_S3_ACCESS_KEY_ID=$STORAGE_S3_ACCESS_KEY_ID"
        "STORAGE_S3_SECRET_ACCESS_KEY=$STORAGE_S3_SECRET_ACCESS_KEY"
        "ORPHAN_REAPER_TOKEN=$ORPHAN_REAPER_TOKEN"
    )
    [[ -z "${STORAGE_S3_REGION:-}" ]] || storage_secrets+=("STORAGE_S3_REGION=$STORAGE_S3_REGION")
    [[ -z "${STORAGE_SCAN_HOOK_URL:-}" ]] || storage_secrets+=("STORAGE_SCAN_HOOK_URL=$STORAGE_SCAN_HOOK_URL")
    [[ -z "${STORAGE_SCAN_HOOK_TOKEN:-}" ]] || storage_secrets+=("STORAGE_SCAN_HOOK_TOKEN=$STORAGE_SCAN_HOOK_TOKEN")
}

require_api_secrets() {
    : "${GOOGLE_SERVER_CLIENT_ID:?GOOGLE_SERVER_CLIENT_ID must be set (from a managed secret store)}"
    : "${API_MINIMUM_CLIENT_VERSION:?API_MINIMUM_CLIENT_VERSION must be set (from a managed secret store)}"
    : "${API_BACKUP_RETENTION_DAYS:?API_BACKUP_RETENTION_DAYS must match provider backup expiry policy}"
    : "${STORAGE_S3_ENDPOINT:?STORAGE_S3_ENDPOINT must be set (from a managed secret store)}"
    : "${STORAGE_S3_BUCKET:?STORAGE_S3_BUCKET must be set (from a managed secret store)}"
    : "${STORAGE_S3_ACCESS_KEY_ID:?STORAGE_S3_ACCESS_KEY_ID must be set (from a managed secret store)}"
    : "${STORAGE_S3_SECRET_ACCESS_KEY:?STORAGE_S3_SECRET_ACCESS_KEY must be set (from a managed secret store)}"
    # The api function refuses to boot without these, so every route - not just passkey sign-in -
    # would answer 500 WORKER_ERROR. Failing here names the cause before anything is deployed.
    : "${PASSKEY_RP_ID:?PASSKEY_RP_ID must be set (from a managed secret store)}"
    : "${PASSKEY_ANDROID_ORIGIN:?PASSKEY_ANDROID_ORIGIN must be set (from a managed secret store)}"

    api_secrets=(
        "GOOGLE_SERVER_CLIENT_ID=$GOOGLE_SERVER_CLIENT_ID"
        "API_MINIMUM_CLIENT_VERSION=$API_MINIMUM_CLIENT_VERSION"
        "API_BACKUP_RETENTION_DAYS=$API_BACKUP_RETENTION_DAYS"
        "STORAGE_S3_ENDPOINT=$STORAGE_S3_ENDPOINT"
        "STORAGE_S3_BUCKET=$STORAGE_S3_BUCKET"
        "STORAGE_S3_ACCESS_KEY_ID=$STORAGE_S3_ACCESS_KEY_ID"
        "STORAGE_S3_SECRET_ACCESS_KEY=$STORAGE_S3_SECRET_ACCESS_KEY"
        "PASSKEY_RP_ID=$PASSKEY_RP_ID"
        "PASSKEY_ANDROID_ORIGIN=$PASSKEY_ANDROID_ORIGIN"
    )
    [[ -z "${STORAGE_S3_REGION:-}" ]] || api_secrets+=("STORAGE_S3_REGION=$STORAGE_S3_REGION")
}

case "$function_name" in
    storage)
        require_storage_secrets
        function_secrets=("${storage_secrets[@]}")
        functions=(storage)
        ;;
    api)
        require_api_secrets
        function_secrets=("${api_secrets[@]}")
        functions=(api)
        ;;
    all)
        require_storage_secrets
        require_api_secrets
        function_secrets=("${storage_secrets[@]}" "${api_secrets[@]}")
        functions=(storage api)
        ;;
    *)
        echo "usage: deploy-storage-function.sh <project-ref> [storage|api|all]" >&2
        exit 2
        ;;
esac

bash scripts/check-migration-versions.sh
supabase link --project-ref "$project_ref"
# No-op when the database is current; otherwise applies the schema the new code depends on.
supabase db push --yes
supabase secrets set "${function_secrets[@]}"
for deployed_function in "${functions[@]}"; do
    supabase functions deploy "$deployed_function"
done
