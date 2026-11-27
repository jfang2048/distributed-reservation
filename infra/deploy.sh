#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/lib.sh"

(( $# <= 2 )) || lab_die 'Usage: ./infra/deploy.sh [jar [profile]]'
jar=${1:-"$LAB_ROOT/build/reservation.jar"}
[[ -s "$jar" ]] || lab_die 'Build build/reservation.jar with ./scripts/build.sh first.'
config=${2:-"$LAB_ROOT/config/cluster.vm.properties"}
[[ -r "$config" ]] || lab_die "Cannot read VM profile: $config"
for node in a b; do
    lab_verify "$node"
done

stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
mkdir -p "$stage/build" "$stage/config"
cp -- "$jar" "$stage/build/reservation.jar"
cp -- "$config" "$stage/config/cluster.properties"
cp -- "$LAB_ROOT/infra/reservation@.service" "$stage/"
for node in a b; do
    tar -C "$stage" -cf - . | lab_ssh "$node" '
        mkdir -p -m 700 reservation-deploy && tar -xmf - -C reservation-deploy
    '
done

for node in a b; do
    lab_ssh "$node" "bash -s -- $node" <<'GUEST'
set -euo pipefail
node=$1
unit="reservation@$node.service"
stage="$HOME/reservation-deploy"
sudo -n install -d -m 755 /opt/reservation
cd /opt/reservation
if systemctl is-active --quiet "$unit"; then
    sudo -n systemctl stop "$unit"
fi
sudo -n install -d -m 755 build config
sudo -n install -d -o reservation -g reservation -m 750 "run/$node"
sudo -n install -m 644 "$stage/build/reservation.jar" build/reservation.jar
sudo -n install -m 644 "$stage/config/cluster.properties" config/cluster.properties
sudo -n install -m 644 "$stage/reservation@.service" /etc/systemd/system/reservation@.service
sudo -n systemctl daemon-reload
GUEST
done

for node in a b; do
    lab_ssh "$node" "sudo -n systemctl enable --now reservation@$node.service"
done
mkdir -p "$LAB_ROOT/run/vm/config"
cp -- "$config" "$LAB_ROOT/run/vm/config/cluster.properties"
printf '%s\n' 'VM servers started. Run the client from run/vm.'
