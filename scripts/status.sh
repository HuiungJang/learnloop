#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

ENV_FILE=".env"
COMPOSE_FILE="docker-compose.install.yml"
WAIT="${1:-}"
AI_CODE_WEB_PORT_OVERRIDE="${AI_CODE_WEB_PORT:-}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing .env. Run ./scripts/install.sh first." >&2
  exit 1
fi

set -a
. "./$ENV_FILE"
set +a

if [ -n "$AI_CODE_WEB_PORT_OVERRIDE" ]; then
  AI_CODE_WEB_PORT="$AI_CODE_WEB_PORT_OVERRIDE"
fi

PORT="${AI_CODE_WEB_PORT:-8080}"
URL="http://localhost:$PORT"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps

if ! command -v curl >/dev/null 2>&1; then
  echo
  echo "curl is not installed; open $URL manually after containers are healthy."
  exit 0
fi

check_health() {
  curl -fsS "$URL/api/health" >/dev/null 2>&1
}

print_runner_status() {
  runner_status=$(curl -fsS "$URL/api/runner/health" 2>/dev/null || true)
  if [ -n "$runner_status" ]; then
    echo "Runner: $runner_status"
  else
    echo "Runner: not available yet"
  fi
}

print_companion_status() {
  if companion_status=$(./scripts/local-ai-companion.sh status 2>/dev/null); then
    echo "Local AI companion: running"
  else
    case "$companion_status" in
      *"not running"*) echo "Local AI companion: degraded - not running" ;;
      *) echo "Local AI companion: degraded - unhealthy" ;;
    esac
  fi
  if [ -n "$companion_status" ]; then
    printf '%s\n' "$companion_status" | sed 's/^/  /'
  fi
}

print_shim_status() {
  shim_status=$(./scripts/local-ai-shim.sh codex status 2>/dev/null || true)
  if [ -n "$shim_status" ]; then
    echo "$shim_status"
  fi
}

if [ "$WAIT" = "--wait" ]; then
  i=0
  while [ "$i" -lt 90 ]; do
    if check_health; then
      echo
      echo "Ready: $URL"
      print_runner_status
      print_companion_status
      print_shim_status
      exit 0
    fi
    i=$((i + 1))
    sleep 2
  done

  echo
  echo "App did not become healthy within 180 seconds." >&2
  echo "Run: docker compose --env-file .env -f docker-compose.install.yml logs --tail=120" >&2
  exit 1
fi

if check_health; then
  echo
  echo "Ready: $URL"
  print_runner_status
  print_companion_status
  print_shim_status
else
  echo
  echo "Not healthy yet: $URL"
  print_runner_status
  print_companion_status
  print_shim_status
fi
