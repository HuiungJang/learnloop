#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

IMAGE=${APP_RUNNER_TYPESCRIPT_IMAGE:-learnloop-runner-typescript:latest}
PASS_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-ts-pass.XXXXXX")
FAIL_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-ts-fail.XXXXXX")

cleanup() {
  rm -rf "$PASS_DIR" "$FAIL_DIR"
}
trap cleanup EXIT INT TERM

write_exercise() {
  target_dir="$1"
  expected="$2"

  mkdir -p "$target_dir/src"
  cat > "$target_dir/src/solution.ts" <<'EOF'
export function add(a: number, b: number): number {
  return a + b;
}
EOF

  cat > "$target_dir/src/solution.test.ts" <<EOF
import { strict as assert } from "node:assert";
import { test } from "node:test";
import { add } from "./solution";

test("adds two numbers", () => {
  assert.equal(add(1, 2), $expected);
});
EOF
}

run_exercise() {
  workspace="$1"

  docker run \
    --rm \
    --network none \
    --cpus 1.0 \
    --memory 512m \
    --pids-limit 128 \
    --security-opt no-new-privileges \
    --cap-drop ALL \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --workdir /workspace \
    --volume "$workspace:/workspace:rw" \
    "$IMAGE"
}

echo "Building $IMAGE"
docker build -t "$IMAGE" runner/typescript

write_exercise "$PASS_DIR" 3
write_exercise "$FAIL_DIR" 4

echo
echo "Running passing TypeScript exercise"
run_exercise "$PASS_DIR"
echo "passing exercise normalized status: passed"

echo
echo "Running failing TypeScript exercise"
set +e
run_exercise "$FAIL_DIR"
FAIL_EXIT=$?
set -e

if [ "$FAIL_EXIT" -eq 0 ]; then
  echo "Expected failing exercise to exit non-zero" >&2
  exit 1
fi

echo "failing exercise normalized status: failed"
