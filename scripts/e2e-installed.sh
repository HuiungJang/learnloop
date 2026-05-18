#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

. ./scripts/env.sh

DEFAULT_NODE_MODULES="/Users/heeung/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules"
if [ -n "${NODE_PATH:-}" ]; then
  :
elif [ -d "$DEFAULT_NODE_MODULES" ]; then
  export NODE_PATH="$DEFAULT_NODE_MODULES"
fi

exec "$NODE_BIN" scripts/e2e-installed.mjs
