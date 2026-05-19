#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

TYPESCRIPT_IMAGE=${APP_RUNNER_TYPESCRIPT_IMAGE:-learnloop-runner-typescript:latest}
JAVA_IMAGE=${APP_RUNNER_JAVA_IMAGE:-learnloop-runner-java:latest}
KOTLIN_IMAGE=${APP_RUNNER_KOTLIN_IMAGE:-learnloop-runner-kotlin:latest}

if [ "${APP_RUNNER_ENABLED:-true}" != "true" ]; then
  echo "Runner is disabled; skipped runner image build."
  exit 0
fi

echo "Building $TYPESCRIPT_IMAGE"
docker build -t "$TYPESCRIPT_IMAGE" runner/typescript

echo "Building $JAVA_IMAGE"
docker build -t "$JAVA_IMAGE" runner/java

echo "Building $KOTLIN_IMAGE"
docker build -t "$KOTLIN_IMAGE" runner/kotlin
