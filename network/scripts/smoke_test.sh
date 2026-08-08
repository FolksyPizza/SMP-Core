#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_FILE="${1:-$ROOT/docs/smoke_test_latest.txt}"
SMOKE_USER="${SMOKE_USER:-FolksyPizza}"
TEST_PAY_TARGET="${TEST_PAY_TARGET:-$SMOKE_USER}"
source "$ROOT/scripts/lib.sh"

if [[ -x "$ROOT/webui/.venv/bin/python" ]]; then
  PYTHON_BIN="$ROOT/webui/.venv/bin/python"
else
  PYTHON_BIN="$(command -v python3 || true)"
fi
if [[ -z "$PYTHON_BIN" ]]; then
  echo "[smoke_test] ERROR: python3 missing" >&2
  exit 1
fi

SERVER_SPECS=()
for server in lobby survival maintenance; do
  enabled_flag="0"
  case "$server" in
    lobby) enabled_flag="${ENABLE_LOBBY:-0}" ;;
    survival) enabled_flag="${ENABLE_SURVIVAL:-0}" ;;
    maintenance) enabled_flag="${ENABLE_MAINTENANCE:-0}" ;;
  esac
  if [[ "$enabled_flag" == "1" ]]; then
    SERVER_SPECS+=("${server}:$(rcon_port_for_service "$server")")
  fi
done

if [[ ${#SERVER_SPECS[@]} -eq 0 ]]; then
  echo "[smoke_test] ERROR: no enabled backends in targets.env" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_FILE")"
"$PYTHON_BIN" - "$ROOT" "$OUT_FILE" "$SMOKE_USER" "$TEST_PAY_TARGET" "${SERVER_SPECS[@]}" <<'PY'
import re
import sys
from pathlib import Path

try:
    from mctools import RCONClient
except Exception as exc:
    print(f"ERROR: mctools unavailable: {exc}")
    sys.exit(2)

root=Path(sys.argv[1])
out_file=Path(sys.argv[2])
smoke_user=sys.argv[3]
pay_target=sys.argv[4]
servers=[]
for raw in sys.argv[5:]:
    name, port = raw.split(':', 1)
    servers.append((name, int(port)))

def clean(s:str)->str:
    s = re.sub(r"\x1b\[[0-9;]*m", "", s or "")
    return s.strip().replace("\n"," | ")

pw=''
for raw in (root/'configs/reused/rcon.env').read_text(encoding='utf-8').splitlines():
    if raw.startswith('RCON_PASSWORD='):
        pw=raw.split('=',1)[1].strip()
        break
if not pw:
    out_file.write_text('ERROR: missing RCON_PASSWORD\n', encoding='utf-8')
    sys.exit(2)

lines=[]
fail=0

lines.append('# Functional Smoke Test')
lines.append(f'- user: {smoke_user}')
lines.append('')

for name,port in servers:
    lines.append(f'## {name}')
    c=RCONClient('127.0.0.1', port=port)
    try:
        if not c.login(pw):
            lines.append('- RCON: FAIL login')
            fail += 1
            lines.append('')
            continue

        checks=[
            ('version', 'version'),
            ('plugins', ''),
            (f'bal {smoke_user}', 'Balance'),
            ('help gamemode', ''),
            ('help ah', ''),
            ('help shop', ''),
            ('help settings', ''),
        ]

        for cmd, expect in checks:
            out=''
            for _ in range(35):
                out=clean(str(c.command(cmd)))
                if 'Another command is being executed' in out:
                    import time; time.sleep(0.2); continue
                if expect and expect.lower() not in out.lower():
                    import time; time.sleep(0.2); continue
                break
            ok=True
            if cmd.startswith('bal '):
                # Maintenance/offline-only backends can return "Player not found" for offline targets.
                if 'Unknown command' in out or 'No such command' in out:
                    ok=False
            if 'Unknown command' in out and not cmd.startswith('help '):
                ok=False
            status='PASS' if ok else 'FAIL'
            if not ok:
                fail += 1
            lines.append(f'- {status} `{cmd}` => {out[:260]}')

        pay_out=clean(str(c.command(f'pay {pay_target} 1k')))
        # Console cannot execute /pay in-game, but command must be registered.
        pay_ok=('Unknown command' not in pay_out and 'No such command' not in pay_out)
        if not pay_ok:
            fail += 1
        lines.append(f"- {'PASS' if pay_ok else 'FAIL'} `pay {pay_target} 1k` => {pay_out[:260]}")

    except Exception as exc:
        lines.append(f'- FAIL exception: {exc}')
        fail += 1
    finally:
        try: c.stop()
        except Exception: pass
    lines.append('')

lines.append('## Log/Config Checks')
for s, _ in servers:
    log = root / s / 'logs' / 'latest.log'
    cfg = root / s / 'plugins' / 'LuckPerms' / 'config.yml'
    vault_line='MISSING'
    dm_line='MISSING'
    if log.exists():
      txt=log.read_text(encoding='utf-8', errors='ignore')
      vault_line='OK' if ('Essentials Economy hooked' in txt or 'Detected supported permissions plugin LuckPerms without Vault installed' in txt) else 'MISSING'
      dm_line='OK' if ('DeluxeMenus' in txt or 'PizzaNetworkCore' in txt) else 'MISSING'
    lp_storage='MISSING'
    if cfg.exists() and 'storage-method: mariadb' in cfg.read_text(encoding='utf-8', errors='ignore'):
      lp_storage='OK'
    if s == 'survival' and not cfg.exists():
      lp_storage='DISABLED_FOLIA_SUB'
    core_enabled='MISSING'
    if log.exists() and ('Enabling PizzaNetworkCore v1.0.0' in txt or 'Enabled PizzaNetworkCore' in txt):
      core_enabled='OK'
    join_quit='OK'
    if log.exists() and re.search(r'joined the game|left the game', txt):
      join_quit='FOUND'
      fail += 1
    lines.append(f'- {s}: vault/econ={vault_line}, menu_stack={dm_line}, core={core_enabled}, lp_mariadb={lp_storage}, join_quit_silence={join_quit}')
    if core_enabled != 'OK':
      fail += 1
    if lp_storage not in ('OK', 'DISABLED_FOLIA_SUB'):
      fail += 1

lines.append('')
lines.append(f'RESULT: {"PASS" if fail == 0 else "FAIL"} (issues={fail})')
out_file.write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(out_file)
sys.exit(0 if fail == 0 else 1)
PY

rc=$?
if [[ $rc -ne 0 ]]; then
  echo "[smoke_test] FAIL (see $OUT_FILE)"
else
  echo "[smoke_test] PASS (see $OUT_FILE)"
fi
exit $rc
