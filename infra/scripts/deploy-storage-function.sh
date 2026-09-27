#!/usr/bin/env bash
# Sets Edge Function secrets and deploys one StudyFlow Supabase Edge Function.
#
# SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by the hosted Edge Function runtime.
#
# Usage: ./deploy-storage-function.sh <project-ref> [storage|api|all]
set -euo pipefail
cd "$(dirname "$0")/.."

project_ref="${1:?usage: deploy-storage-function.sh <project-ref> [storage|api|all]}"
function_name="${2:-storage}"

: "${SUPABASE_ACCESS_TOKEN:?SUPABASE_ACCESS_TOKEN must be set (from a managed secret store)}"

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

    api_secrets=(
        "GOOGLE_SERVER_CLIENT_ID=$GOOGLE_SERVER_CLIENT_ID"
        "API_MINIMUM_CLIENT_VERSION=$API_MINIMUM_CLIENT_VERSION"
    )
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

supabase link --project-ref "$project_ref"
supabase secrets set "${function_secrets[@]}"
for deployed_function in "${functions[@]}"; do
    supabase functions deploy "$deployed_function"
done
