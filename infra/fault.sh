#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

usage() {
    lab_die "Usage: $0 partition a b | heal | crash NODE | restart NODE | poweroff NODE | boot NODE"
}

partition_link() {
    lab_ssh "$1" sudo -n nft -f - <<RULES
add table inet reservation_partition
flush table inet reservation_partition
add chain inet reservation_partition output { type filter hook output priority 0; policy accept; }
add rule inet reservation_partition output ip daddr $(lab_ip "$2") drop
RULES
}

heal_link() {
    lab_ssh "$1" sudo -n bash -s <<'GUEST'
set -euo pipefail
if nft list table inet reservation_partition >/dev/null 2>&1; then
    nft delete table inet reservation_partition
fi
GUEST
}

case ${1-} in
    partition)
        [[ $# == 3 ]] || usage
        [[ ( $2 == a && $3 == b ) || ( $2 == b && $3 == a ) ]] || usage
        lab_verify "$2"
        lab_verify "$3"
        if ! partition_link "$2" "$3" || ! partition_link "$3" "$2"; then
            heal_link "$2" || :
            heal_link "$3" || :
            lab_die "Partition setup failed. Run $0 heal to clear the lab rules."
        fi
        ;;
    heal)
        [[ $# == 1 ]] || usage
        status=0
        for node in a b; do
            if (lab_verify "$node"); then
                heal_link "$node" || status=1
            else
                status=1
            fi
        done
        exit "$status"
        ;;
    crash|restart)
        [[ $# == 2 ]] || usage
        lab_verify "$2"
        if [[ $1 == crash ]]; then
            lab_ssh "$2" sudo -n systemctl kill --kill-whom=main --signal=SIGKILL "reservation@$2.service"
        else
            lab_ssh "$2" sudo -n systemctl restart "reservation@$2.service"
        fi
        ;;
    poweroff|boot)
        [[ $# == 2 ]] || usage
        lab_verify_domain "$2"
        if [[ $1 == poweroff ]]; then
            lab_virsh destroy "$(lab_vm "$2")"
        else
            lab_virsh start "$(lab_vm "$2")"
        fi
        ;;
    *)
        usage
        ;;
esac
