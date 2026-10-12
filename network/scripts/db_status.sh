#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
cd "$ROOT/database"
docker compose ps
