#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/env.sh"

APP_DATA_DIR="${APP_DATA_DIR:-.local-data-smoke}" APP_PORT="${APP_PORT:-4183}" "$NODE_BIN" scripts/smoke.mjs
