#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  echo "Missing .env. Nothing to stop."
  exit 0
fi

./scripts/local-ai-companion.sh stop || true
docker compose --env-file .env -f docker-compose.install.yml down
