#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

ENV_FILE=".env"
COMPOSE_FILE="docker-compose.install.yml"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
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

write_env_file() {
  db_password=$(generate_secret)
  demo_password=$(generate_secret)

  old_umask=$(umask)
  umask 077
  cat > "$ENV_FILE" <<EOF
AI_CODE_WEB_PORT=8080
APP_DATABASE_NAME=learnloop
APP_DATABASE_USERNAME=learnloop
APP_DATABASE_PASSWORD=$db_password
APP_DEMO_PASSWORD=$demo_password
APP_OPENAPI_ENABLED=true
APP_RUNNER_ENABLED=true
APP_RUNNER_BASE_URL=
APP_RUNNER_TOKEN=
APP_RUNNER_REQUIRE_LIMITS=true
EOF
  umask "$old_umask"
}

require_command docker
docker compose version >/dev/null

if [ ! -f "$ENV_FILE" ]; then
  write_env_file
  echo "Created $ENV_FILE with generated local credentials."
else
  echo "Using existing $ENV_FILE."
fi

compose build
compose up -d
./scripts/local-ai-companion.sh start || echo "Local AI companion is not running. Start it with ./scripts/local-ai-companion.sh."

./scripts/status.sh --wait

set -a
. "./$ENV_FILE"
set +a

echo
echo "LearnLoop is installed."
echo "Open: http://localhost:${AI_CODE_WEB_PORT:-8080}"
echo
echo "Demo users:"
echo "- admin@example.com"
echo "- contributor@example.com"
echo "- reviewer@example.com"
echo "- learner@example.com"
echo
echo "Demo password: ${APP_DEMO_PASSWORD}"
echo
echo "Useful commands:"
echo "- ./scripts/start.sh"
echo "- ./scripts/stop.sh"
echo "- ./scripts/status.sh"
echo "- ./scripts/local-ai-companion.sh"
