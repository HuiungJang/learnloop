#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  echo "Missing .env. Run ./install.sh first." >&2
  exit 1
fi

if [ ! -f ".release-version" ]; then
  echo "Missing .release-version. This release bundle is incomplete." >&2
  exit 1
fi

AI_CODE_RELEASE_VERSION=$(tr -d '\r\n' < .release-version)
export AI_CODE_RELEASE_VERSION

docker compose --env-file .env -f docker-compose.yml up -d
./status.sh --wait
