#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

[[ $# == 1 && $1 == --yes ]] ||
    lab_die "Usage: $0 --yes (clears both VM nodes' reservation state)"

for node in a b; do
    lab_verify "$node"
    lab_ssh "$node" sudo -n systemctl cat "reservation@$node.service" >/dev/null
done

for node in a b; do
    lab_ssh "$node" sudo -n systemctl stop "reservation@$node.service"
done

"$LAB_ROOT/infra/fault.sh" heal
"$LAB_ROOT/infra/network.sh" clear

for node in a b; do
    lab_ssh "$node" sudo -n bash -s -- "$LAB_SERVICE_DIR" "$node" <<'GUEST'
set -euo pipefail
run=$1/run
state=$run/$2
[[ ! -L $1 && ! -L $run && ! -L $state ]]
install -d -o reservation -g reservation -m 0750 -- "$state"
find "$state" -mindepth 1 -delete
GUEST
done

for node in a b; do
    lab_ssh "$node" sudo -n systemctl start "reservation@$node.service"
done
