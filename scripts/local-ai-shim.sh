#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

. ./scripts/env.sh

exec "$NODE_BIN" scripts/local-ai-shim.mjs "$@"
