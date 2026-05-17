#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/env.sh"

"$NODE_BIN" --test tests/*.test.js
