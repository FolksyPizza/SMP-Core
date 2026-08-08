#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"

# Always clear maintenance state first so routing is not left stuck in DB.
if [[ -x "$ROOT/scripts/maintenance_mode.sh" ]]; then
  "$ROOT/scripts/maintenance_mode.sh" disable >/dev/null 2>&1 || true
fi

stop_service velocity
stop_service lobby
stop_service survival
stop_service maintenance
stop_service webui

if is_enabled ENABLE_DB && command -v docker >/dev/null 2>&1; then
  if ! (cd "$ROOT/database" && docker compose stop); then
    echo "DB stop skipped: docker compose unavailable."
  fi
fi
