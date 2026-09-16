#!/usr/bin/env bash
# Runs the pgTAP authorisation suite against the local Supabase stack.
#
# Requires the stack to already be running (./dev-up.sh); this script does not start or stop it,
# so it can be run repeatedly against the same environment while iterating on tests.
set -euo pipefail
cd "$(dirname "$0")/.."

supabase test db --local
