#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

./scripts/test.sh
./scripts/backend-test.sh
./scripts/frontend-typecheck.sh
./scripts/frontend-build.sh
