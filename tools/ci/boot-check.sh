#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
# Boot check: start a throwaway Paper server with the built plugins and fail on any plugin error.
#
#   tools/ci/boot-check.sh [jar-dir] [work-dir]     defaults: build/dist, build/boot-check
#
# Downloads a pinned Paper build and the required third-party plugins (checksums verified), starts the server in
# offline mode with File storage (no database), waits for startup, stops it, and checks the log: every suite plugin
# must enable at the suite version, with no SEVERE line, no plugin exception and no failed schema statement.
# PAPER_JAR=<path> boots a local Paper jar instead of the pinned one (checking another supported version).
set -euo pipefail

JARS="$(cd "${1:-build/dist}" && pwd)"
WORK="${2:-build/boot-check}"
PORT="${BOOT_CHECK_PORT:-25590}"
TIMEOUT="${BOOT_CHECK_TIMEOUT:-300}"

PAPER_URL="https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar"
PAPER_SHA="5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"
PROTOCOLLIB_URL="https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar"
PROTOCOLLIB_SHA="ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf"
VAULT_URL="https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar"
VAULT_SHA="a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d"

fetch() { # url sha256 destination
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c --quiet - || { echo "boot-check: checksum mismatch for $1" >&2; exit 2; }
}

rm -rf "$WORK"; mkdir -p "$WORK/plugins"; cd "$WORK"
if [ -n "${PAPER_JAR:-}" ]; then cp "$PAPER_JAR" paper.jar; else fetch "$PAPER_URL" "$PAPER_SHA" paper.jar; fi
fetch "$PROTOCOLLIB_URL" "$PROTOCOLLIB_SHA" plugins/ProtocolLib.jar
fetch "$VAULT_URL" "$VAULT_SHA" plugins/Vault.jar
# Backend plugins only: PizzaProxyGuard runs on Velocity and PizzaLimbo on its own limbo backend.
expected=()
for jar in "$JARS"/Pizza*.jar; do
  name="$(basename "$jar" .jar)"
  case "$name" in PizzaProxyGuard|PizzaLimbo) continue ;; esac
  cp "$jar" plugins/
  expected+=("$name")
done
version="$(unzip -p "$JARS/PizzaNetworkCore.jar" plugin.yml | sed -nE 's/^version:[[:space:]]*//p' | tr -d "\"' ")"

echo "eula=true" > eula.txt
cat > server.properties <<EOF
server-port=$PORT
server-ip=127.0.0.1
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
EOF

mkfifo console
java -Xms512M -Xmx1G -jar paper.jar --nogui < console > server.out 2>&1 &
pid=$!
exec 3> console
end=$((SECONDS + TIMEOUT))
until grep -q 'Done (' server.out 2>/dev/null; do
  if ! kill -0 "$pid" 2>/dev/null; then echo "boot-check: server exited during startup" >&2; tail -40 server.out >&2; exit 1; fi
  if [ $SECONDS -ge $end ]; then echo "boot-check: no startup within ${TIMEOUT}s" >&2; kill "$pid"; exit 1; fi
  sleep 2
done
sleep 10   # let asynchronous startup work (schema checks, storage imports) finish
echo stop >&3
wait "$pid" || true
exec 3>&-

status=0
for name in "${expected[@]}"; do
  plugin="$name"; [ "$name" = PizzaEnderchest ] && plugin=EnderchestExpander
  grep -q "Enabling $plugin v$version" server.out || { echo "boot-check: $plugin did not enable at v$version" >&2; status=1; }
done
problems="$(grep -E 'SEVERE|ERROR\]|Error occurred while enabling|Could not load|schema statement failed|could not apply schema|Exception' server.out \
  | grep -E 'Pizza|EnderchestExpander|dev\.pizzasmp|me\.pizzasmp|schema' || true)"
if [ -n "$problems" ]; then echo "boot-check: plugin errors:" >&2; echo "$problems" >&2; status=1; fi
[ $status -eq 0 ] && echo "boot-check: ${#expected[@]} plugins enabled at v$version, no plugin errors"
exit $status
