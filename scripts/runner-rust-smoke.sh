#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

IMAGE=${APP_RUNNER_RUST_IMAGE:-learnloop-runner-rust:latest}
PASS_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-rust-pass.XXXXXX")
FAIL_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-rust-fail.XXXXXX")
COMPILE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-rust-compile.XXXXXX")
MISSING_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-rust-missing.XXXXXX")

cleanup() {
  rm -rf "$PASS_DIR" "$FAIL_DIR" "$COMPILE_DIR" "$MISSING_DIR"
}
trap cleanup EXIT INT TERM

write_exercise() {
  target_dir="$1"
  expected="$2"

  mkdir -p "$target_dir/src" "$target_dir/tests"
  cat > "$target_dir/Cargo.toml" <<'EOF'
[package]
name = "learnloop_practice"
version = "0.1.0"
edition = "2021"
publish = false

[lib]
path = "src/lib.rs"
EOF

  cat > "$target_dir/src/lib.rs" <<'EOF'
pub fn add(a: i32, b: i32) -> i32 {
    a + b
}
EOF

  cat > "$target_dir/tests/solution_test.rs" <<EOF
use learnloop_practice::add;

#[test]
fn adds_two_numbers() {
    assert_eq!(add(1, 2), $expected);
}
EOF
}

run_exercise() {
  workspace="$1"

  docker run \
    --rm \
    --pull never \
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
docker build -t "$IMAGE" runner/rust

write_exercise "$PASS_DIR" 3
write_exercise "$FAIL_DIR" 4
write_exercise "$COMPILE_DIR" 3
printf 'pub fn broken( {\n' > "$COMPILE_DIR/src/lib.rs"
mkdir -p "$MISSING_DIR/src"
printf 'pub fn add(a: i32, b: i32) -> i32 { a + b }\n' > "$MISSING_DIR/src/lib.rs"

echo
echo "Running passing Rust exercise"
run_exercise "$PASS_DIR"
echo "passing exercise normalized status: passed"

echo
echo "Running failing Rust exercise"
set +e
run_exercise "$FAIL_DIR"
FAIL_EXIT=$?
set -e
if [ "$FAIL_EXIT" -eq 0 ]; then
  echo "Expected failing exercise to exit non-zero" >&2
  exit 1
fi
echo "failing exercise normalized status: failed"

echo
echo "Running compile-error Rust exercise"
set +e
run_exercise "$COMPILE_DIR"
COMPILE_EXIT=$?
set -e
if [ "$COMPILE_EXIT" -ne 2 ]; then
  echo "Expected compile error exercise to exit 2, got $COMPILE_EXIT" >&2
  exit 1
fi
echo "compile-error exercise normalized status: compile_error"

echo
echo "Running missing-manifest Rust exercise"
set +e
run_exercise "$MISSING_DIR"
MISSING_EXIT=$?
set -e
if [ "$MISSING_EXIT" -ne 2 ]; then
  echo "Expected missing manifest exercise to exit 2, got $MISSING_EXIT" >&2
  exit 1
fi
echo "missing-manifest exercise normalized status: compile_error"
