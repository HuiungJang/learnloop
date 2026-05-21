#!/usr/bin/env sh
set -eu

if [ ! -f Package.swift ]; then
  echo "not ok 1 - swift test"
  echo "Package.swift is required" >&2
  exit 2
fi

if ! find . -name '*.swift' -not -path './.build/*' | grep -q .; then
  echo "not ok 1 - swift test"
  echo "At least one Swift file is required" >&2
  exit 2
fi

mkdir -p .learnloop/home .learnloop/tmp .learnloop/clang-module-cache .learnloop/swiftpm .learnloop/build
export HOME=/workspace/.learnloop/home
export TMPDIR=/workspace/.learnloop/tmp
export CLANG_MODULE_CACHE_PATH=/workspace/.learnloop/clang-module-cache
export SWIFTPM_HOME=/workspace/.learnloop/swiftpm

OUTPUT_FILE=$(mktemp)
set +e
swift test --scratch-path .learnloop/build >"$OUTPUT_FILE" 2>&1
STATUS=$?
set -e

cat "$OUTPUT_FILE"

if [ "$STATUS" -eq 0 ]; then
  echo "ok 1 - swift test"
  exit 0
fi

echo "not ok 1 - swift test"

if grep -qiE 'error:|compilation failed|emit-module command failed|invalid manifest|no such module' "$OUTPUT_FILE"; then
  exit 2
fi

exit 1
