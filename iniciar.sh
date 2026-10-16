#!/bin/bash
set -eu
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAR="$SCRIPT_DIR/target/breakinv-0.5.0.jar"
if [ ! -f "$JAR" ]; then
    printf 'Build not found: %s. Run mvn clean package first.\n' "$JAR" >&2
    exit 1
fi
exec java "$@" -jar "$JAR"
