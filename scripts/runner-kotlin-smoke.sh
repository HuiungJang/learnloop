#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

IMAGE=${APP_RUNNER_KOTLIN_IMAGE:-learnloop-runner-kotlin:latest}
PASS_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-kotlin-pass.XXXXXX")
FAIL_DIR=$(mktemp -d "${TMPDIR:-/tmp}/learnloop-runner-kotlin-fail.XXXXXX")

cleanup() {
  rm -rf "$PASS_DIR" "$FAIL_DIR"
}
trap cleanup EXIT INT TERM

write_exercise() {
  target_dir="$1"
  expected="$2"

  mkdir -p "$target_dir/src/main/kotlin/learnloop" "$target_dir/src/test/kotlin/learnloop"
  cat > "$target_dir/src/main/kotlin/learnloop/Solution.kt" <<'EOF'
package learnloop

object Solution {
    fun add(left: Int, right: Int): Int = left + right
}
EOF

  cat > "$target_dir/src/test/kotlin/learnloop/SolutionTest.kt" <<EOF
package learnloop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SolutionTest {
    @Test
    fun addsNumbers() {
        assertEquals($expected, Solution.add(1, 2))
    }
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
docker build -t "$IMAGE" runner/kotlin

write_exercise "$PASS_DIR" 3
write_exercise "$FAIL_DIR" 4

echo
echo "Running passing Kotlin exercise"
run_exercise "$PASS_DIR"
echo "passing exercise normalized status: passed"

echo
echo "Running failing Kotlin exercise"
set +e
run_exercise "$FAIL_DIR"
FAIL_EXIT=$?
set -e

if [ "$FAIL_EXIT" -eq 0 ]; then
  echo "Expected failing exercise to exit non-zero" >&2
  exit 1
fi

echo "failing exercise normalized status: failed"
