#!/bin/sh
# Gradle wrapper script for UN*X
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

MAX_FD=maximum
warn() { echo "$*"; }
die() { echo; echo "ERROR: $*"; echo; exit 1; }

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
case "$OS" in
  cygwin* | msys* | mingw*) APP_HOME=$(cygpath --unix "$APP_HOME") ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

eval set -- $DEFAULT_JVM_OPTS "$JAVA_OPTS" "$GRADLE_OPTS" \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "\"$CLASSPATH\"" \
    org.gradle.wrapper.GradleWrapperMain "$@"

exec "$JAVACMD" "$@"
