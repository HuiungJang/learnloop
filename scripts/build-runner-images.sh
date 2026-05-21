#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

IMAGE_VERSION=${APP_RUNNER_IMAGE_VERSION:-latest}
REGISTRY_PREFIX=
if [ "${APP_RUNNER_IMAGE_REGISTRY:-}" != "" ]; then
  REGISTRY_PREFIX=$(printf '%s' "$APP_RUNNER_IMAGE_REGISTRY" | sed 's:/*$::')
  REGISTRY_PREFIX="$REGISTRY_PREFIX/"
fi

TYPESCRIPT_IMAGE=${APP_RUNNER_TYPESCRIPT_IMAGE:-${REGISTRY_PREFIX}learnloop-runner-typescript:$IMAGE_VERSION}
JAVA_IMAGE=${APP_RUNNER_JAVA_IMAGE:-${REGISTRY_PREFIX}learnloop-runner-java:$IMAGE_VERSION}
KOTLIN_IMAGE=${APP_RUNNER_KOTLIN_IMAGE:-${REGISTRY_PREFIX}learnloop-runner-kotlin:$IMAGE_VERSION}
SWIFT_IMAGE=${APP_RUNNER_SWIFT_IMAGE:-${REGISTRY_PREFIX}learnloop-runner-swift:$IMAGE_VERSION}
RUST_IMAGE=${APP_RUNNER_RUST_IMAGE:-${REGISTRY_PREFIX}learnloop-runner-rust:$IMAGE_VERSION}
RUNNER_LANGUAGES=${RUNNER_LANGUAGES:-typescript java kotlin swift rust}

if [ "${APP_RUNNER_ENABLED:-true}" != "true" ]; then
  echo "Runner is disabled; skipped runner image build."
  exit 0
fi

for language in $RUNNER_LANGUAGES; do
  case "$language" in
    typescript)
      echo "Building $TYPESCRIPT_IMAGE"
      docker build -t "$TYPESCRIPT_IMAGE" runner/typescript
      ;;
    java)
      echo "Building $JAVA_IMAGE"
      docker build -t "$JAVA_IMAGE" runner/java
      ;;
    kotlin)
      echo "Building $KOTLIN_IMAGE"
      docker build -t "$KOTLIN_IMAGE" runner/kotlin
      ;;
    swift)
      echo "Building $SWIFT_IMAGE"
      docker build -t "$SWIFT_IMAGE" runner/swift
      ;;
    rust)
      echo "Building $RUST_IMAGE"
      docker build -t "$RUST_IMAGE" runner/rust
      ;;
    *)
      echo "Unsupported runner language: $language" >&2
      exit 1
      ;;
  esac
done
