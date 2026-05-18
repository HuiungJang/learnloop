#!/usr/bin/env sh
set -eu

WORKSPACE=${LEARNLOOP_WORKSPACE:-/workspace}
JUNIT_JAR=/opt/learnloop-runner/junit-platform-console-standalone.jar
KOTLIN_LIB=/opt/kotlin/lib
KOTLIN_RUNTIME="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlin-stdlib-jdk7.jar:$KOTLIN_LIB/kotlin-stdlib-jdk8.jar:$KOTLIN_LIB/kotlin-reflect.jar:$KOTLIN_LIB/kotlin-test.jar:$KOTLIN_LIB/kotlin-test-junit5.jar"
KOTLIN_JAVA_OPTS=${JAVA_OPTS:-"-Xmx512M -Xms128M"}
META_DIR="$WORKSPACE/.learnloop"
OUT_DIR="$META_DIR/out/classes"
SOURCES_FILE="$META_DIR/kotlin-sources.txt"
JANSI_DIR="$META_DIR/jansi"

rm -rf "$META_DIR/out"
mkdir -p "$OUT_DIR" "$JANSI_DIR"

find "$WORKSPACE" \
  -path "$META_DIR" -prune -o \
  -name '*.kt' -type f -print \
  | sort > "$SOURCES_FILE"

if [ ! -s "$SOURCES_FILE" ]; then
  echo "No Kotlin source files found. Add at least one *.kt file." >&2
  exit 2
fi

if ! JAVA_OPTS="$KOTLIN_JAVA_OPTS -Djansi.tmpdir=$JANSI_DIR -Djansi.mode=strip" \
  kotlinc @"$SOURCES_FILE" -jvm-target 21 -cp "$JUNIT_JAR:$KOTLIN_RUNTIME" -d "$OUT_DIR"; then
  exit 2
fi

java -Djansi.tmpdir="$JANSI_DIR" -Djansi.mode=strip -jar "$JUNIT_JAR" \
  execute \
  --disable-ansi-colors \
  --class-path "$OUT_DIR:$KOTLIN_RUNTIME" \
  --scan-class-path
