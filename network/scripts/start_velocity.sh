#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
SESSION_NAME="${VELOCITY_SCREEN_SESSION:-pizzasmp_velocity}"
JAVA_BIN="${JAVA_BIN:-java}"
export JAVA_OPTS="${VELOCITY_JAVA_OPTS:--Xms512M -Xmx1G -XX:+UseG1GC}"
SCREEN_INSTALL_SCRIPT="$ROOT/needs_sudo/install_screen.sh"

if ! command -v screen >/dev/null 2>&1; then
  echo "[velocity] ERROR: GNU screen is required for velocity console control."
  mkdir -p "$ROOT/needs_sudo"
  cat > "$SCREEN_INSTALL_SCRIPT" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update
sudo apt-get install -y screen
EOF
  chmod +x "$SCREEN_INSTALL_SCRIPT"
  echo "[velocity] Run: ./needs_sudo/install_screen.sh"
  exit 1
fi

if screen_session_exists "${SESSION_NAME}"; then
  if lsof -nP -iTCP:"${VELOCITY_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[velocity] already running in screen session ${SESSION_NAME}"
    pid_from_port "${VELOCITY_PORT}" >"$(pid_file velocity)" || true
    exit 0
  fi
  screen -S "${SESSION_NAME}" -X quit || true
fi

CMD="cd '$ROOT/velocity' && exec '$JAVA_BIN' ${JAVA_OPTS} -jar '$ROOT/velocity/velocity.jar' >'$(log_file velocity)' 2>&1"
screen -L -Logfile "$(log_file velocity)" -dmS "${SESSION_NAME}" bash -lc "$CMD"

for _ in $(seq 1 60); do
  if lsof -nP -iTCP:"${VELOCITY_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    pid_from_port "${VELOCITY_PORT}" >"$(pid_file velocity)" || true
    echo "[velocity] started in screen session ${SESSION_NAME}"
    exit 0
  fi
  sleep 1
done

echo "[velocity] ERROR: session started but port ${VELOCITY_PORT} did not open in time."
exit 1
