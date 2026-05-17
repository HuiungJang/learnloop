#!/usr/bin/env sh
set -eu

DEFAULT_NODE="/Users/heeung/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"

if [ -n "${NODE_BIN:-}" ]; then
  :
elif [ -x "$DEFAULT_NODE" ]; then
  NODE_BIN="$DEFAULT_NODE"
else
  NODE_BIN="node"
fi

export NODE_BIN
