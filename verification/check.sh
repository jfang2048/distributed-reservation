#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
mode=${1:-check}
if [[ $# -gt 1 || ( $mode != check && $mode != --negative-model ) ]]; then
    echo 'Usage: ./verification/check.sh [--negative-model]' >&2
    exit 2
fi

cache="$root/build/verification"
mkdir -p -- "$cache"
jar="$cache/tla2tools.jar"
sha=936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88
if [[ ! -f $jar ]]; then
    download=$(mktemp "$cache/tla2tools.XXXXXX")
    trap 'unlink "$download"' EXIT
    curl -fsSL --retry 2 https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar -o "$download"
    printf '%s  %s\n' "$sha" "$download" | sha256sum --check --status
    mv -- "$download" "$jar"
    trap - EXIT
fi
printf '%s  %s\n' "$sha" "$jar" | sha256sum --check

run=$(mktemp -d "$cache/run.XXXXXX")
cp -- "$root"/verification/AtomicReservation.{tla,cfg} "$run/"
if [[ $mode == --negative-model ]]; then
    sed -i 's/AllowEarlyCommit = FALSE/AllowEarlyCommit = TRUE/' "$run/AtomicReservation.cfg"
fi
echo "TLC output: $run/tlc.log"
if (cd "$run" && "$java" -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers 1 -deadlock AtomicReservation) | tee "$run/tlc.log"; then
    if [[ $mode == --negative-model ]]; then
        echo 'Expected an AtomicCommit counterexample.' >&2
        exit 1
    fi
else
    result=$?
    if [[ $mode == --negative-model ]] && grep -Fq 'Invariant AtomicCommit is violated' "$run/tlc.log"; then
        exit 0
    fi
    exit "$result"
fi

"$root/scripts/build.sh"
export RESERVATION_JAVA="$java" RESERVATION_JAR="$root/build/reservation.jar"
PYTHONPATH="$root/tests/integration" python3 -B -m unittest -vf \
    test_cluster.ReservationTest.test_participant_restarts_before_vote \
    test_cluster.ReservationTest.test_all_ready_locks_survive_participant_restart \
    test_cluster.ReservationTest.test_peer_votes_abort_after_partial_prepare \
    test_cluster.ReservationTest.test_peer_learns_commit_after_partial_delivery
