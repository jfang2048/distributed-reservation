#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$root"

mapfile -d '' -t sources < <(find src -type f -name '*.java' -print0)
if ((${#sources[@]} == 0)); then
    echo 'Add Java sources under src/, including reservation.Main, then run scripts/build.sh.' >&2
    exit 1
fi

javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
jar=${JAVA_HOME:+$JAVA_HOME/bin/}jar
if ! command -v "$javac" >/dev/null || ! command -v "$jar" >/dev/null; then
    echo 'Install JDK 17 or later and set JAVA_HOME or put javac and jar on PATH.' >&2
    exit 1
fi

mkdir -p build
work=$(mktemp -d build/.compile.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/classes"
"$javac" --release 17 -encoding UTF-8 -d "$work/classes" "${sources[@]}"
if [[ ! -f "$work/classes/reservation/Main.class" ]]; then
    echo 'Add the reservation.Main entry point to the Java sources.' >&2
    exit 1
fi
"$jar" --create --file "$work/reservation.jar" --main-class reservation.Main -C "$work/classes" .
mv -f -- "$work/reservation.jar" build/reservation.jar
printf '%s\n' build/reservation.jar
