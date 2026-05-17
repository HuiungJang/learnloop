#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  echo "Missing .env. Run ./scripts/install.sh first." >&2
  exit 1
fi

docker compose --env-file .env -f docker-compose.install.yml up -d
./scripts/status.sh --wait

