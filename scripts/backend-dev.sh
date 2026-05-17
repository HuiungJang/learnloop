#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-local}
exec ./gradlew :backend:bootRun --args="--spring.profiles.active=$SPRING_PROFILES_ACTIVE"
