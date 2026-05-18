#!/usr/bin/env sh
set -eu

WORKSPACE=${LEARNLOOP_WORKSPACE:-/workspace}
JUNIT_JAR=/opt/learnloop-runner/junit-platform-console-standalone.jar
META_DIR="$WORKSPACE/.learnloop"
OUT_DIR="$META_DIR/out/classes"
SOURCES_FILE="$META_DIR/java-sources.txt"

rm -rf "$META_DIR/out"
mkdir -p "$OUT_DIR"

find "$WORKSPACE" \
  -path "$META_DIR" -prune -o \
  -name '*.java' -type f -print \
  | sort > "$SOURCES_FILE"

if [ ! -s "$SOURCES_FILE" ]; then
  echo "No Java source files found. Add at least one *.java file." >&2
  exit 2
fi

javac -encoding UTF-8 -cp "$JUNIT_JAR" -d "$OUT_DIR" @"$SOURCES_FILE"
java -jar "$JUNIT_JAR" execute --disable-ansi-colors --class-path "$OUT_DIR" --scan-class-path
