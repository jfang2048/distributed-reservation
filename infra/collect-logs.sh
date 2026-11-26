#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

[[ $# == 0 ]] || lab_die "Usage: $0"
mkdir -p -- "$LAB_STATE/logs"
output=$(mktemp -d "$LAB_STATE/logs/$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")
status=0

for node in a b; do
    if ! (lab_verify "$node"); then
        status=1
        continue
    fi

    if lab_ssh "$node" sudo -n journalctl --unit "reservation@$node.service" --output short-iso --no-pager > "$output/$node.journal.log.part"; then
        mv -- "$output/$node.journal.log.part" "$output/$node.journal.log"
    else
        status=1
    fi
    if lab_ssh "$node" sudo -n tar -C "$LAB_SERVICE_DIR/run" -czf - "$node" > "$output/$node.wal.tar.gz.part"; then
        mv -- "$output/$node.wal.tar.gz.part" "$output/$node.wal.tar.gz"
    else
        status=1
    fi
done

printf '%s\n' "$output"
exit "$status"
