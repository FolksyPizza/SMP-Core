#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
# Merge the game-menu and limbo datapacks into one pack that every backend loads.
#   scripts/build-network-dialogs.sh <menu pack dir> <limbo pack dir> <output pack dir>
#
# A proxy that skips the configuration phase on server switches (the Canopy Session Gateway's seamless and
# no-respawn modes) leaves the client holding the first backend's registries. Dialogs and their tags are synced
# registry data, so every backend must load exactly the same dialog set. The merged pack carries both dialog
# namespaces; the pause menu shows the game menu everywhere (the limbo announces maintenance by title, chat and
# hotbar instead). Install the output in each backend's main world datapacks/ in place of both source packs.
set -euo pipefail
MENU="${1:?usage: $0 <menu pack> <limbo pack> <out>}"; LIMBO="${2:?}"; OUT="${3:?}"
[ -f "$MENU/pack.mcmeta" ] && [ -f "$LIMBO/pack.mcmeta" ] || { echo "both inputs must be datapacks" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/data"
cp -r "$MENU/data/." "$OUT/data/"
for ns in "$LIMBO"/data/*/; do
  ns="$(basename "$ns")"
  [ "$ns" = minecraft ] && continue            # the limbo's tags are not merged: one pause menu everywhere
  cp -r "$LIMBO/data/$ns" "$OUT/data/"
done
python3 - "$MENU/pack.mcmeta" "$OUT/pack.mcmeta" <<'PY'
import json, sys
meta = json.load(open(sys.argv[1]))
meta["pack"]["description"] = "Network dialogs: game menu and maintenance (identical on every backend)"
json.dump(meta, open(sys.argv[2], "w"), indent=2)
PY
echo "built $OUT"
