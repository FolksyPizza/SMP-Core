#!/usr/bin/env bash
# Start the dev backend — the plugin development/test server.
#
# Dev is a normal Paper backend on :25568 (the retired pvp port) running the same plugin
# set as survival so tests are representative. It is registered in velocity.toml but kept
# OUT of `try` and out of sync.reconnect, so no player is ever routed here by a fallback
# or by last-logout routing; you reach it deliberately with /server dev.
#
# It shares the production database on purpose, so bugs reproduce against real data.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
export JAVA_OPTS="${DEV_JAVA_OPTS:--Xms512M -Xmx2G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC}"
start_java_service dev "$ROOT/dev" "$ROOT/dev/server.jar"
