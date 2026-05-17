#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/env.sh"

APP_DATA_DIR="${APP_DATA_DIR:-.local-data}" APP_PORT="${APP_PORT:-4173}" "$NODE_BIN" src/server.js
