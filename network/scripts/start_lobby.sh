#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
export JAVA_OPTS="${LOBBY_JAVA_OPTS:--Xms1G -Xmx2G -XX:+UseG1GC}"
start_java_service lobby "$ROOT/lobby" "$ROOT/lobby/server.jar"
