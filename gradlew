#!/bin/sh
set -e

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JAVACMD=${JAVA_HOME:+$JAVA_HOME/bin/java}

if [ -z "${JAVACMD:-}" ]; then
  JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
  echo "Java is required to run Gradle." >&2
  exit 1
fi

exec "$JAVACMD" \
  -Xmx64m \
  -Xms64m \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
