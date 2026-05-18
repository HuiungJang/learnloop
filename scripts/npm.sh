#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/env.sh"

DEFAULT_NPM_CLI="/opt/homebrew/lib/node_modules/npm/bin/npm-cli.js"

export PATH="$(dirname "$NODE_BIN"):$PATH"

if [ -n "${NPM_CLI:-}" ]; then
  exec "$NODE_BIN" "$NPM_CLI" "$@"
elif [ -f "$DEFAULT_NPM_CLI" ]; then
  exec "$NODE_BIN" "$DEFAULT_NPM_CLI" "$@"
elif command -v npm >/dev/null 2>&1; then
  exec npm "$@"
else
  echo "npm was not found. Set NPM_CLI to the npm-cli.js path or install npm." >&2
  exit 1
fi
