#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  echo "Missing .env. Run ./scripts/install.sh first." >&2
  exit 1
fi

if ! grep -q "^APP_RUNNER_DOCKER_SOCKET=" .env; then
  printf '%s=%s\n' APP_RUNNER_DOCKER_SOCKET /var/run/docker.sock >> .env
fi
if ! grep -q "^APP_RUNNER_WORKSPACE_HOST_ROOT=" .env; then
  printf '%s=%s\n' APP_RUNNER_WORKSPACE_HOST_ROOT "$ROOT_DIR/.local-runner-workspaces" >> .env
fi
if ! grep -q "^APP_CREDENTIAL_ENCRYPTION_KEY=" .env; then
  if command -v openssl >/dev/null 2>&1; then
    credential_key=$(openssl rand -base64 32)
  else
    credential_key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  fi
  printf '%s=%s\n' APP_CREDENTIAL_ENCRYPTION_KEY "$credential_key" >> .env
fi
if ! grep -q "^APP_RUNNER_IMAGE_REGISTRY=" .env; then
  printf '%s=\n' APP_RUNNER_IMAGE_REGISTRY >> .env
fi
if ! grep -q "^APP_RUNNER_IMAGE_VERSION=" .env; then
  printf '%s=%s\n' APP_RUNNER_IMAGE_VERSION latest >> .env
fi

set -a
. ./.env
set +a

if [ "${APP_RUNNER_ENABLED:-true}" = "true" ]; then
  mkdir -p "${APP_RUNNER_WORKSPACE_HOST_ROOT:?APP_RUNNER_WORKSPACE_HOST_ROOT is required}"
  chmod 1777 "$APP_RUNNER_WORKSPACE_HOST_ROOT"
  docker compose --env-file .env -f docker-compose.install.yml -f docker-compose.runner.yml up -d
else
  docker compose --env-file .env -f docker-compose.install.yml up -d
fi

./scripts/local-ai-companion.sh start || echo "Local AI companion is not running. Start it with ./scripts/local-ai-companion.sh start."
./scripts/status.sh --wait
