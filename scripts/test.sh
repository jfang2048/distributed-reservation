#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac

command -v "$java" >/dev/null || { echo 'Put Java on PATH or set JAVA_HOME.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Install Python 3 to run the tests.' >&2; exit 1; }

"$root/scripts/build.sh"
jar="$root/build/reservation.jar"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
"$javac" --release 17 -Xlint:all -Werror -cp "$jar" -d "$work" "$root/tests/unit/UnitTests.java"
"$java" -cp "$jar:$work" reservation.UnitTests
RESERVATION_JAVA="$java" RESERVATION_JAR="$jar" \
    python3 -B -m unittest discover -s "$root/tests/integration" -v
