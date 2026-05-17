#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/env.sh"

DEFAULT_NPM_CLI="/opt/homebrew/lib/node_modules/npm/bin/npm-cli.js"

if [ -n "${NPM_CLI:-}" ]; then
  :
elif [ -f "$DEFAULT_NPM_CLI" ]; then
  NPM_CLI="$DEFAULT_NPM_CLI"
else
  echo "npm CLI was not found. Set NPM_CLI to the npm-cli.js path." >&2
  exit 1
fi

export PATH="$(dirname "$NODE_BIN"):$PATH"

exec "$NODE_BIN" "$NPM_CLI" "$@"
