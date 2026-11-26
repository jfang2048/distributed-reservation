# shellcheck shell=bash
LAB_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
LAB_STATE="$LAB_ROOT/run/lab"
LAB_SERVICE_DIR=/opt/reservation

lab_die() {
    printf '%s\n' "$*" >&2
    exit 1
}

lab_ip() {
    case "${1:-}" in
        a) printf '%s\n' 192.168.151.11 ;;
        b) printf '%s\n' 192.168.151.12 ;;
        *) lab_die 'Select lab node a or b.' ;;
    esac
}

lab_vm() {
    case "${1:-}" in
        a|b) printf 'reservation-%s\n' "$1" ;;
        *) lab_die 'Select lab node a or b.' ;;
    esac
}

lab_virsh() {
    LC_ALL=C virsh --connect qemu:///system "$@"
}

lab_ssh() {
    local address
    address=$(lab_ip "$1") || return
    shift
    [[ -f "$LAB_STATE/id_ed25519" ]] || lab_die 'Run ./infra/provision.sh first.'
    ssh -F /dev/null -o BatchMode=yes -o ConnectTimeout=5 -o IdentitiesOnly=yes \
        -o StrictHostKeyChecking=accept-new -o GlobalKnownHostsFile=/dev/null \
        -o "UserKnownHostsFile=$LAB_STATE/known_hosts" -i "$LAB_STATE/id_ed25519" \
        "lab@$address" "$@"
}

lab_verify_domain() {
    local node=$1 name mac
    name=$(lab_vm "$node") || return
    mac="52:54:00:51:00:0$node"
    [[ $(lab_virsh desc "$name" --config) == distributed-reservation-lab ]] ||
        lab_die "Expected $name to belong to the reservation lab."
    lab_virsh domiflist "$name" |
        awk -v mac="$mac" '$2=="network" && $3=="reservation-lab" && tolower($5)==mac { found=1 } END { exit !found }' ||
        lab_die "Expected $name on reservation-lab with MAC $mac."
}

lab_verify() {
    lab_verify_domain "$1"
    [[ $(lab_ssh "$1" 'cat /etc/reservation-lab' </dev/null) == "$1" ]] ||
        lab_die "Expected the lab marker for node $1."
}
