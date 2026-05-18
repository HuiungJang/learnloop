#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

. ./scripts/env.sh

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

detect_os() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    Linux) echo "linux" ;;
    *) uname -s | tr '[:upper:]' '[:lower:]' ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    arm64 | aarch64) echo "arm64" ;;
    x86_64 | amd64) echo "amd64" ;;
    *) uname -m | tr '[:upper:]' '[:lower:]' ;;
  esac
}

write_checksum() {
  target="$1"
  target_dir=$(dirname "$target")
  target_file=$(basename "$target")

  if command -v shasum >/dev/null 2>&1; then
    (cd "$target_dir" && shasum -a 256 "$target_file" > "$target_file.sha256")
  elif command -v sha256sum >/dev/null 2>&1; then
    (cd "$target_dir" && sha256sum "$target_file" > "$target_file.sha256")
  else
    echo "Checksum tool not found; skipped $target.sha256" >&2
  fi
}

VERSION=${VERSION:-$("$NODE_BIN" -e "const fs = require('fs'); console.log(JSON.parse(fs.readFileSync('package.json', 'utf8')).version)")}
OS_NAME=${OS_NAME:-$(detect_os)}
ARCH_NAME=${ARCH_NAME:-$(detect_arch)}
OUTPUT_DIR=${OUTPUT_DIR:-dist/release}
INCLUDE_POSTGRES_IMAGE=${INCLUDE_POSTGRES_IMAGE:-true}

PACKAGE_NAME="ai-code-learning-platform-$VERSION-$OS_NAME-$ARCH_NAME"
BACKEND_IMAGE="ai-code-learning-platform-backend:$VERSION"
WEB_IMAGE="ai-code-learning-platform-web:$VERSION"
POSTGRES_IMAGE="postgres:16-alpine"

require_command docker
require_command tar
docker compose version >/dev/null

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ai-code-release.XXXXXX")
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

BUILD_ENV="$TMP_DIR/build.env"
cat > "$BUILD_ENV" <<EOF
AI_CODE_WEB_PORT=8080
APP_DATABASE_NAME=aicodelearning
APP_DATABASE_USERNAME=aicodelearning
APP_DATABASE_PASSWORD=release-build-only
APP_DEMO_PASSWORD=release-build-only
APP_OPENAPI_ENABLED=true
EOF

echo "Building release images for $PACKAGE_NAME"
docker compose --env-file "$BUILD_ENV" -f docker-compose.install.yml build backend web
docker tag ai-code-learning-platform-backend:latest "$BACKEND_IMAGE"
docker tag ai-code-learning-platform-web:latest "$WEB_IMAGE"

if [ "$INCLUDE_POSTGRES_IMAGE" = "true" ]; then
  if ! docker image inspect "$POSTGRES_IMAGE" >/dev/null 2>&1; then
    docker pull "$POSTGRES_IMAGE"
  fi
fi

STAGING_DIR="$TMP_DIR/$PACKAGE_NAME"
mkdir -p "$STAGING_DIR/images" "$OUTPUT_DIR"

cp packaging/release-bundle/README.md "$STAGING_DIR/README.md"
cp packaging/release-bundle/docker-compose.yml "$STAGING_DIR/docker-compose.yml"
cp packaging/release-bundle/install.sh "$STAGING_DIR/install.sh"
cp packaging/release-bundle/start.sh "$STAGING_DIR/start.sh"
cp packaging/release-bundle/status.sh "$STAGING_DIR/status.sh"
cp packaging/release-bundle/stop.sh "$STAGING_DIR/stop.sh"
cp .env.example "$STAGING_DIR/.env.example"
cp LICENSE "$STAGING_DIR/LICENSE"
cp NOTICE "$STAGING_DIR/NOTICE"
printf '%s\n' "$VERSION" > "$STAGING_DIR/.release-version"

chmod +x "$STAGING_DIR/install.sh" "$STAGING_DIR/start.sh" "$STAGING_DIR/status.sh" "$STAGING_DIR/stop.sh"

if [ "$OS_NAME" = "macos" ] && [ -d "packaging/macos-app/AI Code Learning Platform.app" ]; then
  cp -R "packaging/macos-app/AI Code Learning Platform.app" "$STAGING_DIR/"
  chmod +x "$STAGING_DIR/AI Code Learning Platform.app/Contents/MacOS/AI Code Learning Platform"
fi

echo "Saving Docker images"
docker save "$BACKEND_IMAGE" -o "$STAGING_DIR/images/backend.tar"
docker save "$WEB_IMAGE" -o "$STAGING_DIR/images/web.tar"
if [ "$INCLUDE_POSTGRES_IMAGE" = "true" ]; then
  docker save "$POSTGRES_IMAGE" -o "$STAGING_DIR/images/postgres.tar"
fi

(
  cd "$STAGING_DIR"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 images/*.tar > checksums.txt
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum images/*.tar > checksums.txt
  fi
)

ARCHIVE="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
tar -C "$TMP_DIR" -czf "$ARCHIVE" "$PACKAGE_NAME"
write_checksum "$ARCHIVE"

DMG=""
if [ "$OS_NAME" = "macos" ] && command -v hdiutil >/dev/null 2>&1; then
  DMG="$OUTPUT_DIR/$PACKAGE_NAME.dmg"
  hdiutil create -volname "AI Code Learning $VERSION" -srcfolder "$STAGING_DIR" -ov -format UDZO "$DMG" >/dev/null
  write_checksum "$DMG"
fi

echo
echo "Release bundle created:"
echo "$ARCHIVE"
if [ -n "$DMG" ]; then
  echo "$DMG"
fi
