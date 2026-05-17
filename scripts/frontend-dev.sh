#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

exec ./scripts/npm.sh --prefix frontend run dev -- --host 127.0.0.1
