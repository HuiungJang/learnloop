#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
cd "$ROOT_DIR"

ENV_FILE=".env"
COMPOSE_FILE="docker-compose.yml"
RELEASE_VERSION_FILE=".release-version"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

load_release_version() {
  if [ ! -f "$RELEASE_VERSION_FILE" ]; then
    echo "Missing $RELEASE_VERSION_FILE. This release bundle is incomplete." >&2
    exit 1
  fi

  AI_CODE_RELEASE_VERSION=$(tr -d '\r\n' < "$RELEASE_VERSION_FILE")
  export AI_CODE_RELEASE_VERSION
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
  project_name=${AI_CODE_PROJECT_NAME:-learnloop}
  web_port=${AI_CODE_WEB_PORT:-8080}
  db_name=${APP_DATABASE_NAME:-learnloop}
  db_user=${APP_DATABASE_USERNAME:-learnloop}
  openapi_enabled=${APP_OPENAPI_ENABLED:-true}
  runner_enabled=${APP_RUNNER_ENABLED:-true}
  runner_base_url=${APP_RUNNER_BASE_URL:-}
  runner_token=${APP_RUNNER_TOKEN:-}
  runner_image=${APP_RUNNER_IMAGE:-learnloop-runner:latest}
  runner_require_limits=${APP_RUNNER_REQUIRE_LIMITS:-true}

  old_umask=$(umask)
  umask 077
  cat > "$ENV_FILE" <<EOF
AI_CODE_PROJECT_NAME=$project_name
AI_CODE_WEB_PORT=$web_port
APP_DATABASE_NAME=$db_name
APP_DATABASE_USERNAME=$db_user
APP_DATABASE_PASSWORD=$db_password
APP_DEMO_PASSWORD=$demo_password
APP_OPENAPI_ENABLED=$openapi_enabled
APP_RUNNER_ENABLED=$runner_enabled
APP_RUNNER_BASE_URL=$runner_base_url
APP_RUNNER_TOKEN=$runner_token
APP_RUNNER_IMAGE=$runner_image
APP_RUNNER_REQUIRE_LIMITS=$runner_require_limits
EOF
  umask "$old_umask"
}

load_images() {
  if [ ! -d "images" ]; then
    echo "Missing images directory. This release bundle is incomplete." >&2
    exit 1
  fi

  loaded=0
  for image_tar in images/*.tar; do
    if [ ! -f "$image_tar" ]; then
      continue
    fi

    echo "Loading $image_tar"
    docker load -i "$image_tar" >/dev/null
    loaded=$((loaded + 1))
  done

  if [ "$loaded" -eq 0 ]; then
    echo "No Docker image archives found in images/." >&2
    exit 1
  fi
}

validate_env_file() {
  set -a
  . "./$ENV_FILE"
  set +a

  if [ "${APP_DATABASE_PASSWORD:-}" = "change-me" ] || [ "${APP_DEMO_PASSWORD:-}" = "change-me" ]; then
    echo "$ENV_FILE still contains placeholder credentials. Delete it and rerun ./install.sh, or set real values." >&2
    exit 1
  fi
}

require_command docker
docker compose version >/dev/null
load_release_version

if [ ! -f "$ENV_FILE" ]; then
  write_env_file
  echo "Created $ENV_FILE with generated local credentials."
else
  echo "Using existing $ENV_FILE."
fi

validate_env_file
load_images
compose up -d

./status.sh --wait

set -a
. "./$ENV_FILE"
set +a

echo
echo "LearnLoop $AI_CODE_RELEASE_VERSION is installed."
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
echo "- ./start.sh"
echo "- ./stop.sh"
echo "- ./status.sh"
