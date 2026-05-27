#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

ENV_FILE=".env"
COMPOSE_FILE="docker-compose.install.yml"
RUNNER_COMPOSE_FILE="docker-compose.runner.yml"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

compose() {
  load_env_file
  if [ "${APP_RUNNER_ENABLED:-true}" = "true" ]; then
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$RUNNER_COMPOSE_FILE" "$@"
  else
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
  fi
}

ensure_env_value() {
  key="$1"
  value="$2"
  if ! grep -q "^$key=" "$ENV_FILE"; then
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

load_env_file() {
  set -a
  . "./$ENV_FILE"
  set +a
}

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c 1-24
  elif [ -r /dev/urandom ]; then
    LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24
  else
    echo "Cannot generate credentials securely. Install openssl and retry." >&2
    exit 1
  fi
}

generate_encryption_key() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32
  elif [ -r /dev/urandom ]; then
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  else
    echo "Cannot generate encryption key securely. Install openssl and retry." >&2
    exit 1
  fi
}

write_env_file() {
  db_password=$(generate_secret)
  demo_password=$(generate_secret)
  credential_key=$(generate_encryption_key)

  old_umask=$(umask)
  umask 077
  cat > "$ENV_FILE" <<EOF
AI_CODE_WEB_PORT=8080
APP_DATABASE_NAME=learnloop
APP_DATABASE_USERNAME=learnloop
APP_DATABASE_PASSWORD=$db_password
APP_DEMO_PASSWORD=$demo_password
APP_CREDENTIAL_ENCRYPTION_KEY=$credential_key
APP_OPENAPI_ENABLED=true
APP_RUNNER_ENABLED=true
APP_RUNNER_BASE_URL=
APP_RUNNER_TOKEN=
APP_RUNNER_REQUIRE_LIMITS=true
APP_RUNNER_IMAGE_REGISTRY=
APP_RUNNER_IMAGE_VERSION=latest
APP_RUNNER_DOCKER_SOCKET=/var/run/docker.sock
APP_RUNNER_WORKSPACE_HOST_ROOT=$ROOT_DIR/.local-runner-workspaces
EOF
  umask "$old_umask"
}

ensure_runner_env() {
  ensure_env_value APP_CREDENTIAL_ENCRYPTION_KEY "$(generate_encryption_key)"
  ensure_env_value APP_RUNNER_DOCKER_SOCKET /var/run/docker.sock
  ensure_env_value APP_RUNNER_WORKSPACE_HOST_ROOT "$ROOT_DIR/.local-runner-workspaces"
  ensure_env_value APP_RUNNER_IMAGE_REGISTRY ""
  ensure_env_value APP_RUNNER_IMAGE_VERSION latest
}

prepare_runner_workspace() {
  load_env_file
  if [ "${APP_RUNNER_ENABLED:-true}" != "true" ]; then
    return
  fi
  mkdir -p "${APP_RUNNER_WORKSPACE_HOST_ROOT:?APP_RUNNER_WORKSPACE_HOST_ROOT is required}"
  chmod 1777 "$APP_RUNNER_WORKSPACE_HOST_ROOT"
}

build_runner_images() {
  load_env_file
  if [ "${APP_RUNNER_ENABLED:-true}" != "true" ]; then
    echo "Runner is disabled; skipped runner image build."
    return
  fi
  RUNNER_LANGUAGES="${APP_RUNNER_BUILD_LANGUAGES:-typescript java kotlin}" APP_RUNNER_ENABLED="${APP_RUNNER_ENABLED:-true}" ./scripts/build-runner-images.sh
}

require_command docker
docker compose version >/dev/null

if [ ! -f "$ENV_FILE" ]; then
  write_env_file
  echo "Created $ENV_FILE with generated local credentials."
else
  echo "Using existing $ENV_FILE."
fi

ensure_runner_env
prepare_runner_workspace
compose build
build_runner_images
compose up -d
./scripts/local-ai-companion.sh start || echo "Local AI companion is not running. Start it with ./scripts/local-ai-companion.sh start."

./scripts/status.sh --wait

load_env_file

echo
echo "LearnLoop is installed."
echo "Open: http://localhost:${AI_CODE_WEB_PORT:-8080}"
echo
echo "Local owner:"
echo "- ${APP_LOCAL_OWNER_EMAIL:-owner@local.learnloop}"
echo
echo "Local owner password: ${APP_DEMO_PASSWORD}"
echo
echo "Useful commands:"
echo "- ./scripts/start.sh"
echo "- ./scripts/stop.sh"
echo "- ./scripts/status.sh"
echo "- ./scripts/local-ai-companion.sh"
echo "- ./scripts/local-ai-shim.sh codex install"
echo "- ./scripts/build-runner-images.sh"
