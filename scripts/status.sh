#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

ENV_FILE=".env"
COMPOSE_FILE="docker-compose.install.yml"
WAIT="${1:-}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing .env. Run ./scripts/install.sh first." >&2
  exit 1
fi

set -a
. "./$ENV_FILE"
set +a

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

if [ "$WAIT" = "--wait" ]; then
  i=0
  while [ "$i" -lt 90 ]; do
    if check_health; then
      echo
      echo "Ready: $URL"
      print_runner_status
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
else
  echo
  echo "Not healthy yet: $URL"
  print_runner_status
fi
