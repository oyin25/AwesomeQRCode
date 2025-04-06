#!/usr/bin/env sh

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# Attempt to set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    JAVA_PATH=`which java`
    if [ -n "$JAVA_PATH" ]; then
        JAVA_HOME=`dirname $(dirname "$JAVA_PATH")`
    fi
fi

if [ -z "$JAVA_HOME" ]; then
    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

JAVA_CMD="$JAVA_HOME/bin/java"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVA_CMD" $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
