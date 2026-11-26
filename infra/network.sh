#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

[[ $# == 1 && ( $1 == wan || $1 == clear ) ]] ||
    lab_die "Usage: $0 wan|clear"
mode=$1

set_link() {
    lab_ssh "$1" sudo -n bash -s -- "$2" "$(lab_ip "$3")" <<'GUEST'
set -euo pipefail
mode=$1
peer=$2
route=$(ip -4 route get "$peer")
device=$(awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i+1); exit } }' <<< "$route")
[[ -n $device ]]
root=$(tc qdisc show dev "$device" root)

if [[ $mode == clear ]]; then
    if [[ $root == "qdisc prio 7a10: root "* ]]; then
        tc qdisc del dev "$device" root handle 7a10:
    fi
    exit
fi

if [[ $root == "qdisc prio 7a10: root "* ]]; then
    tc qdisc del dev "$device" root handle 7a10:
elif [[ $root != "qdisc "*' 0: root '* && -n $root ]]; then
    printf 'Existing root qdisc on %s: %s\n' "$device" "$root" >&2
    exit 1
fi

tc qdisc replace dev "$device" root handle 7a10: prio bands 3 \
    priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
tc qdisc replace dev "$device" parent 7a10:3 handle 7a11: netem delay 80ms 15ms loss 1%
tc filter replace dev "$device" parent 7a10: protocol ip prio 10 u32 \
    match ip dst "$peer/32" flowid 7a10:3
GUEST
}

lab_verify a
lab_verify b

if [[ $mode == wan ]]; then
    if ! set_link a wan b || ! set_link b wan a; then
        set_link a clear b || :
        set_link b clear a || :
        lab_die "WAN setup failed. Run $0 clear to clear the lab queues."
    fi
else
    status=0
    set_link a clear b || status=1
    set_link b clear a || status=1
    exit "$status"
fi
