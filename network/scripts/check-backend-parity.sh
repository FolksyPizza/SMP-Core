#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
# Check that backends match wherever a configuration-free server switch needs them to.
#   scripts/check-backend-parity.sh <server dir> <server dir> [...]
#
# The Canopy Session Gateway's seamless and no-respawn switches skip the configuration phase and (no-respawn) the
# join packet, so the client keeps what the first backend sent. Backends must agree on: the server build,
# datapacks (dialogs and tags are synced registries), enabled feature packs, and the join settings the client keeps
# (view and simulation distance, hardcore). Exit status 1 lists every difference.
set -euo pipefail
[ $# -ge 2 ] || { echo "usage: $0 <server dir> <server dir> [...]" >&2; exit 2; }
python3 - "$@" <<'PY'
import hashlib, os, re, sys
KEYS = ["view-distance", "simulation-distance", "hardcore", "initial-enabled-packs", "initial-disabled-packs"]
def props(d):
    out = {}
    for line in open(os.path.join(d, "server.properties"), encoding="utf-8"):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1); out[k] = v
    return out
def tree_hash(path):
    h = hashlib.sha256()
    for root, dirs, files in sorted(os.walk(path)):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(root, f)
            h.update(os.path.relpath(p, path).encode()); h.update(open(p, "rb").read())
    return h.hexdigest()[:12]
def describe(d):
    p = props(d)
    world = os.path.join(d, p.get("level-name", "world"), "datapacks")
    packs = {n: tree_hash(os.path.join(world, n)) for n in sorted(os.listdir(world))} if os.path.isdir(world) else {}
    jars = sorted(f for f in os.listdir(d) if re.match(r"(paper|folia|purpur).*\.jar$", f))
    build = hashlib.sha256(open(os.path.join(d, jars[0]), "rb").read()).hexdigest()[:12] if jars else "missing"
    return {"server build": build, "datapacks": packs, **{k: p.get(k, "") for k in KEYS}}
servers = {d: describe(d) for d in sys.argv[1:]}
first = next(iter(servers.values()))
problems = 0
for key in first:
    values = {d: s[key] for d, s in servers.items()}
    if len({repr(v) for v in values.values()}) > 1:
        problems += 1
        print(f"DIFFERS  {key}")
        for d, v in values.items(): print(f"    {d}: {v}")
    else:
        print(f"same     {key}")
sys.exit(1 if problems else 0)
PY
