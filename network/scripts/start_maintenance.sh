#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
export JAVA_OPTS="${MAINTENANCE_JAVA_OPTS:--Xms1G -Xmx2G -XX:+UseG1GC}"
start_java_service maintenance "$ROOT/maintenance" "$ROOT/maintenance/server.jar"
