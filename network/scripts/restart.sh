#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
"$ROOT/scripts/maintenance_mode.sh" disable >/dev/null 2>&1 || true

# Reverse-order stop keeps dependencies up while backends drain.
"$ROOT/scripts/stop_only.sh" webui || true
"$ROOT/scripts/stop_only.sh" maintenance || true
"$ROOT/scripts/stop_only.sh" survival || true
"$ROOT/scripts/stop_only.sh" lobby || true
"$ROOT/scripts/stop_only.sh" velocity || true

if is_enabled ENABLE_DB; then
  "$ROOT/scripts/db_stop.sh" >/dev/null 2>&1 || true
fi

"$ROOT/scripts/start.sh"
