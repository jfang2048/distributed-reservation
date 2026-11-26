#!/usr/bin/env bash
set -euo pipefail

case ${1:-} in
    server|reserve) ;;
    *)
        echo 'Usage: scripts/local.sh server --node a|b  OR  scripts/local.sh reserve ITEM...' >&2
        exit 2
        ;;
esac

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
jar="$root/build/reservation.jar"
if [[ ! -r "$jar" ]]; then
    echo 'Run scripts/build.sh to create build/reservation.jar.' >&2
    exit 1
fi

java=${JAVA_HOME:+$JAVA_HOME/bin/}java
if ! command -v "$java" >/dev/null; then
    echo 'Install Java 17 or later and set JAVA_HOME or put java on PATH.' >&2
    exit 1
fi

profile="$root/config/cluster.local.properties"
if [[ ! -r "$profile" ]]; then
    echo 'Restore config/cluster.local.properties before starting the local lab.' >&2
    exit 1
fi

runtime="$root/run/local"
mkdir -p -- "$runtime/config"
staged=$(mktemp "$runtime/config/.cluster.XXXXXX")
trap 'rm -f -- "$staged"' EXIT
cp -- "$profile" "$staged"
mv -f -- "$staged" "$runtime/config/cluster.properties"
cd -- "$runtime"
exec "$java" -jar "$jar" "$@"
