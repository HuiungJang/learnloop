#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  echo "Missing .env. Nothing to stop."
  exit 0
fi

if [ ! -f ".release-version" ]; then
  echo "Missing .release-version. This release bundle is incomplete." >&2
  exit 1
fi

AI_CODE_RELEASE_VERSION=$(tr -d '\r\n' < .release-version)
export AI_CODE_RELEASE_VERSION

./local-ai-companion.sh stop || true
docker compose --env-file .env -f docker-compose.yml down
