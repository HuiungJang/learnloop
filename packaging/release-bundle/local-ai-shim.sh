#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
cd "$ROOT_DIR"

NODE_BIN="${NODE_BIN:-node}"

if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
  echo "Node.js is required for the Codex shim manager. Install Node.js or set NODE_BIN." >&2
  exit 1
fi

exec "$NODE_BIN" local-ai-shim.mjs "$@"
