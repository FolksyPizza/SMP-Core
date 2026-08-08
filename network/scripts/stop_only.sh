#!/usr/bin/env bash
# Stop one service (or all).
#
# Drives off all_backends() rather than a hardcoded case list. The old list omitted
# `dev` entirely, so `stop_only.sh dev` silently fell through to the usage branch and
# stopped nothing — which is how dev kept running a stale jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
service="${1:-}"

case "$service" in
  webui) exec "$ROOT/scripts/stop_webui.sh" ;;
  all)   exec "$ROOT/scripts/stop.sh" ;;
  velocity) stop_service velocity; exit 0 ;;
esac

for b in $(all_backends); do
  if [[ "$service" == "$b" ]]; then
    stop_service "$b"
    # stop_service is best-effort; make sure the port is genuinely free so a follow-up
    # start is not skipped by the "already running" check.
    port="$(service_port "$b")"
    if [[ -n "$port" ]] && ! wait_for_port_closed "$port" 30; then
      echo "[$b] port $port still bound after graceful stop — killing the JVM" >&2
      pkill -f "$b/server.jar" 2>/dev/null || true
      sleep 2
    fi
    exit 0
  fi
done

echo "Usage: $0 <$(all_backends | tr ' ' '|')|velocity|webui|all>" >&2
exit 1
