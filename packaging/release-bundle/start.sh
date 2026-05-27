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

if ! grep -q "^APP_RUNNER_DOCKER_SOCKET=" .env; then
  printf '%s=%s\n' APP_RUNNER_DOCKER_SOCKET /var/run/docker.sock >> .env
fi
if ! grep -q "^APP_RUNNER_WORKSPACE_HOST_ROOT=" .env; then
  printf '%s=%s\n' APP_RUNNER_WORKSPACE_HOST_ROOT "$ROOT_DIR/.local-runner-workspaces" >> .env
fi
if ! grep -q "^APP_RUNNER_BUILD_CONTEXT_ROOT=" .env; then
  printf '%s=%s\n' APP_RUNNER_BUILD_CONTEXT_ROOT /app/runner >> .env
fi

AI_CODE_RELEASE_VERSION=$(tr -d '\r\n' < .release-version)
export AI_CODE_RELEASE_VERSION

if [ -f ".release-runner.env" ]; then
  set -a
  . ./.release-runner.env
  set +a
fi
if ! grep -q "^APP_RUNNER_IMAGE_REGISTRY=" .env; then
  printf '%s=%s\n' APP_RUNNER_IMAGE_REGISTRY "${RELEASE_RUNNER_IMAGE_REGISTRY:-}" >> .env
fi
if ! grep -q "^APP_RUNNER_IMAGE_SOURCE=" .env; then
  if [ "${RELEASE_RUNNER_IMAGE_SOURCE:-}" != "" ]; then
    printf '%s=%s\n' APP_RUNNER_IMAGE_SOURCE "$RELEASE_RUNNER_IMAGE_SOURCE" >> .env
  elif [ "${RELEASE_RUNNER_IMAGE_MODE:-online}" = "offline" ]; then
    printf '%s=%s\n' APP_RUNNER_IMAGE_SOURCE bundled >> .env
  else
    printf '%s=%s\n' APP_RUNNER_IMAGE_SOURCE registry >> .env
  fi
fi
if ! grep -q "^APP_RUNNER_IMAGE_VERSION=" .env; then
  printf '%s=%s\n' APP_RUNNER_IMAGE_VERSION "${RELEASE_RUNNER_IMAGE_VERSION:-$AI_CODE_RELEASE_VERSION}" >> .env
fi

set -a
. ./.env
set +a

if [ "${APP_RUNNER_ENABLED:-true}" = "true" ]; then
  mkdir -p "${APP_RUNNER_WORKSPACE_HOST_ROOT:?APP_RUNNER_WORKSPACE_HOST_ROOT is required}"
  chmod 1777 "$APP_RUNNER_WORKSPACE_HOST_ROOT"
  docker compose --env-file .env -f docker-compose.yml -f docker-compose.runner.yml up -d
else
  docker compose --env-file .env -f docker-compose.yml up -d
fi

./local-ai-companion.sh start || echo "Local AI companion is not running. Start it with ./local-ai-companion.sh."
./status.sh --wait
