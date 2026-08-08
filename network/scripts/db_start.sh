#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
cd "$ROOT/database"
# Plain `up -d` recreates ONLY when the compose config actually changed, and reuses the
# container otherwise — so maintenance actions stay quiet, but an edit to the compose file
# is applied instead of silently ignored. This used to pass --no-recreate, which meant a
# changed environment (the TZ setting, for one) never reached the running container and the
# fix looked applied when it was not.
docker compose up -d
