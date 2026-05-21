#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

IMAGE=${APP_RUNNER_SWIFT_IMAGE:-learnloop-runner-swift:latest}
PASS_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-swift-pass.XXXXXX")
FAIL_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-swift-fail.XXXXXX")
COMPILE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-swift-compile.XXXXXX")
MISSING_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-swift-missing.XXXXXX")

cleanup() {
  rm -rf "$PASS_DIR" "$FAIL_DIR" "$COMPILE_DIR" "$MISSING_DIR"
}
trap cleanup EXIT INT TERM

write_exercise() {
  target_dir="$1"
  expected="$2"

  mkdir -p "$target_dir/Sources/LearnLoopPractice" "$target_dir/Tests/LearnLoopPracticeTests"
  cat > "$target_dir/Package.swift" <<'EOF'
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LearnLoopPractice",
    products: [
        .library(name: "LearnLoopPractice", targets: ["LearnLoopPractice"])
    ],
    targets: [
        .target(name: "LearnLoopPractice"),
        .testTarget(name: "LearnLoopPracticeTests", dependencies: ["LearnLoopPractice"])
    ]
)
EOF

  cat > "$target_dir/Sources/LearnLoopPractice/Solution.swift" <<'EOF'
public func add(_ a: Int, _ b: Int) -> Int {
    a + b
}
EOF

  cat > "$target_dir/Tests/LearnLoopPracticeTests/SolutionTests.swift" <<EOF
import Testing
@testable import LearnLoopPractice

@Test func addsTwoNumbers() {
    #expect(add(1, 2) == $expected)
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
docker build -t "$IMAGE" runner/swift

write_exercise "$PASS_DIR" 3
write_exercise "$FAIL_DIR" 4
write_exercise "$COMPILE_DIR" 3
printf 'public func broken( {\n' > "$COMPILE_DIR/Sources/LearnLoopPractice/Solution.swift"
mkdir -p "$MISSING_DIR/Sources/LearnLoopPractice"
printf 'public func add(_ a: Int, _ b: Int) -> Int { a + b }\n' > "$MISSING_DIR/Sources/LearnLoopPractice/Solution.swift"

echo
echo "Running passing Swift exercise"
run_exercise "$PASS_DIR"
echo "passing exercise normalized status: passed"

echo
echo "Running failing Swift exercise"
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
echo "Running compile-error Swift exercise"
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
echo "Running missing-manifest Swift exercise"
set +e
run_exercise "$MISSING_DIR"
MISSING_EXIT=$?
set -e
if [ "$MISSING_EXIT" -ne 2 ]; then
  echo "Expected missing manifest exercise to exit 2, got $MISSING_EXIT" >&2
  exit 1
fi
echo "missing-manifest exercise normalized status: compile_error"
