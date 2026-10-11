#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
# Merge configs/proxy/lang/messages-overrides.properties into a Velocity proxy's lang/ files.
#   scripts/apply-proxy-messages.sh <proxy dir>
# Replaces only the keys in the overrides file, in messages.properties and every English variant. Keeps a
# .bak of each file the first time. Restart the proxy afterwards.
set -euo pipefail
PROXY="${1:?usage: $0 <proxy dir>}"
OVR="$(dirname "$(realpath "$0")")/../configs/proxy/lang/messages-overrides.properties"
[ -d "$PROXY/lang" ] || { echo "no lang/ in $PROXY (start the proxy once so Velocity writes it)" >&2; exit 1; }
for f in "$PROXY"/lang/messages.properties "$PROXY"/lang/messages_en*.properties; do
  [ -f "$f" ] || continue
  [ -f "$f.bak" ] || cp -p "$f" "$f.bak"
  python3 - "$f" "$OVR" <<'PY'
import sys
target, overrides = sys.argv[1], sys.argv[2]
new = {}
for line in open(overrides, encoding="utf-8"):
    if line.strip() and not line.lstrip().startswith("#") and "=" in line:
        k, v = line.rstrip("\n").split("=", 1)
        new[k.strip()] = v
out, seen = [], set()
for line in open(target, encoding="utf-8"):
    key = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None
    if key in new:
        out.append(f"{key}={new[key]}\n"); seen.add(key)
    else:
        out.append(line)
out += [f"{k}={v}\n" for k, v in new.items() if k not in seen]
open(target, "w", encoding="utf-8").writelines(out)
PY
  echo "updated $f"
done
