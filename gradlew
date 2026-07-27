#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
  JAVA_CMD=java
fi
exec "$JAVA_CMD" -Dcontrolled.wrapper.projectDir="$APP_HOME" -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
