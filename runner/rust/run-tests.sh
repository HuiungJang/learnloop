#!/usr/bin/env sh
set -eu

if [ ! -f Cargo.toml ]; then
  echo "not ok 1 - cargo test"
  echo "Cargo.toml is required" >&2
  exit 2
fi

if ! find . -name '*.rs' -not -path './target/*' | grep -q .; then
  echo "not ok 1 - cargo test"
  echo "At least one Rust file is required" >&2
  exit 2
fi

mkdir -p .learnloop/cargo-home
export CARGO_HOME=/workspace/.learnloop/cargo-home

OUTPUT_FILE=$(mktemp)
set +e
if [ -f Cargo.lock ]; then
  cargo test --locked >"$OUTPUT_FILE" 2>&1
else
  cargo test >"$OUTPUT_FILE" 2>&1
fi
STATUS=$?
set -e

cat "$OUTPUT_FILE"

if [ "$STATUS" -eq 0 ]; then
  echo "ok 1 - cargo test"
  exit 0
fi

echo "not ok 1 - cargo test"

if grep -qiE 'error\[|could not compile|failed to parse manifest|no targets specified|lock file needs to be updated' "$OUTPUT_FILE"; then
  exit 2
fi

exit 1
