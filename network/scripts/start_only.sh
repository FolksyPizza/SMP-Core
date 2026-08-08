#!/usr/bin/env bash
# Start one service (or all).
#
#   start_only.sh <service>            start it (no-op if already listening)
#   start_only.sh <service> --restart  stop first, then start
#
# --restart exists because start_java_service returns early when the port is already
# bound. That is right for "make sure it is up", but wrong after a jar swap: the old
# process keeps serving the old code and the deploy looks applied when it is not.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
service="${1:-}"
mode="${2:-}"

if [[ "$mode" == "--restart" && "$service" != "all" ]]; then
  "$ROOT/scripts/stop_only.sh" "$service" || true
  sleep 2
fi

case "$service" in
  velocity) exec "$ROOT/scripts/start_velocity.sh" ;;
  webui)    exec "$ROOT/scripts/start_webui.sh" ;;
  all)      exec "$ROOT/scripts/start.sh" ;;
esac

for b in $(all_backends); do
  if [[ "$service" == "$b" ]]; then
    starter="$ROOT/scripts/start_${b}.sh"
    [[ -x "$starter" ]] || { echo "No start script for '$b' at $starter" >&2; exit 1; }
    exec "$starter"
  fi
done

echo "Usage: $0 <$(all_backends | tr ' ' '|')|velocity|webui|all> [--restart]" >&2
exit 1
