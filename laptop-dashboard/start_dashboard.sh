#!/bin/zsh
set -euo pipefail

dashboard_dir="${0:A:h}"
cd "$dashboard_dir"

collector_args=(
  --meshtastic-port "${MESHTASTIC_PORT:-/dev/cu.usbmodem1101}"
)

if [[ -n "${RETICULUM_PORT:-}" ]]; then
  collector_args+=(
    --reticulum-port "$RETICULUM_PORT"
    --reticulum-frequency "${RETICULUM_FREQUENCY:-925875000}"
    --reticulum-bandwidth "${RETICULUM_BANDWIDTH:-250000}"
    --reticulum-txpower "${RETICULUM_TXPOWER:-17}"
    --reticulum-sf "${RETICULUM_SF:-9}"
    --reticulum-cr "${RETICULUM_CR:-5}"
  )
fi

uv run python service/monitor.py "${collector_args[@]}" &
collector_pid=$!
trap 'kill "$collector_pid" 2>/dev/null || true' EXIT INT TERM

npm run dev
