#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/run/lab"
pool=default
uri=qemu:///system
network=reservation-lab
network_uuid=8cdbaa24-c4a1-47c4-bad2-c10595151001
description=distributed-reservation-lab
image_url=https://cloud-images.ubuntu.com/releases/noble/release
image_name=ubuntu-24.04-server-cloudimg-amd64.img
keyring=/usr/share/keyrings/ubuntu-cloudimage-keyring.gpg
image="$state/images/$image_name"

die() {
    printf '%s\n' "$*" >&2
    exit 1
}

vmctl() {
    LC_ALL=C virsh --connect "$uri" "$@"
}

if [[ ${1:-} == --help && $# -eq 1 ]]; then
    printf 'Usage: %s\nCreate and start reservation-a and reservation-b on the reservation-lab network.\n' "$0"
    exit 0
fi
[[ $# -eq 0 ]] || die "Usage: $0"
[[ $(uname -m) == x86_64 ]] || die 'The lab uses an x86-64 Ubuntu image.'
for tool in virsh virt-install qemu-img genisoimage curl gpgv sha256sum flock ssh ssh-keygen ip timeout; do
    command -v "$tool" >/dev/null || die "Install $tool to provision the lab."
done
[[ -r $keyring ]] || die "Install ubuntu-keyring to get $keyring."
vmctl pool-info "$pool" >/dev/null

umask 077
mkdir -p -- "$state/images"
exec 9>"$state/provision.lock"
flock -n 9 || die 'Lab provisioning is already running.'
temp=$(mktemp -d "$state/provision.XXXXXXXX")
vm=
created_volumes=()
cleanup() {
    if [[ -n $vm ]] && ! vmctl dominfo "$vm" >/dev/null 2>&1; then
        for volume in "${created_volumes[@]}"; do
            vmctl vol-delete "$volume" --pool "$pool" >/dev/null || :
        done
    fi
    find "$temp" -depth -delete
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for node in a b; do
    name="reservation-$node"
    if vmctl dominfo "$name" >/dev/null 2>&1; then
        [[ $(vmctl desc "$name" --config) == "$description" ]] ||
            die "Domain $name belongs to another setup."
    fi
done

if vmctl net-info "$network" >/dev/null 2>&1; then
    [[ $(vmctl net-uuid "$network") == "$network_uuid" ]] ||
        die "Network $network belongs to another setup."
else
    [[ -z $(ip -4 route show exact 192.168.151.0/24) ]] ||
        die '192.168.151.0/24 already has a host route.'
    cat > "$temp/network.xml" <<XML
<network>
  <name>$network</name>
  <uuid>$network_uuid</uuid>
  <forward mode='nat'/>
  <bridge name='virbr-reslab'/>
  <ip address='192.168.151.1' netmask='255.255.255.0'>
    <dhcp>
      <host mac='52:54:00:51:00:0a' name='reservation-a' ip='192.168.151.11'/>
      <host mac='52:54:00:51:00:0b' name='reservation-b' ip='192.168.151.12'/>
    </dhcp>
  </ip>
</network>
XML
    vmctl net-define "$temp/network.xml"
fi
if ! vmctl net-list --name | grep -Fxq "$network"; then
    vmctl net-start "$network"
fi
vmctl net-autostart "$network" >/dev/null

key="$state/id_ed25519"
if [[ ! -f $key ]]; then
    for node in a b; do
        if vmctl dominfo "reservation-$node" >/dev/null 2>&1; then
            die "Restore $key to access the existing lab VMs."
        fi
    done
    ssh-keygen -q -t ed25519 -N '' -C reservation-lab -f "$key"
fi
read -r key_type key_data _ < <(ssh-keygen -y -f "$key")
[[ $key_type == ssh-ed25519 && $key_data =~ ^[A-Za-z0-9+/=]+$ ]] ||
    die 'The lab SSH key must use Ed25519.'

fetch() {
    curl --fail --silent --show-error --location --retry 2 --proto '=https' "$1" --output "$2"
}

verify_image() {
    local checksum=$1 file=$2
    printf '%s  %s\n' "$checksum" "$file" | sha256sum --check --status
}

get_image() {
    local checksum
    fetch "$image_url/SHA256SUMS" "$temp/SHA256SUMS"
    fetch "$image_url/SHA256SUMS.gpg" "$temp/SHA256SUMS.gpg"
    gpgv --keyring "$keyring" "$temp/SHA256SUMS.gpg" "$temp/SHA256SUMS"
    checksum=$(awk -v name="$image_name" '$2 == name || $2 == "*" name { print $1 }' "$temp/SHA256SUMS")
    [[ $checksum =~ ^[a-f0-9]{64}$ ]] || die "Missing checksum for $image_name."
    if ! verify_image "$checksum" "$image"; then
        printf 'Downloading %s\n' "$image_name"
        fetch "$image_url/$image_name" "$temp/$image_name"
        verify_image "$checksum" "$temp/$image_name" || die "Checksum mismatch for $image_name."
        mv -- "$temp/$image_name" "$image"
    fi
}

upload_volume() {
    local name=$1 source=$2 capacity=$3 format=$4
    vmctl vol-create-as "$pool" "$name" "$capacity" --format "$format"
    created_volumes+=("$name")
    vmctl vol-upload "$name" "$source" --pool "$pool"
}

for node in a b; do
    vm="reservation-$node"
    if vmctl dominfo "$vm" >/dev/null 2>&1; then
        if [[ $(vmctl domstate "$vm") == 'shut off' ]]; then
            vmctl start "$vm"
        fi
        continue
    fi
    disk="$vm.qcow2"
    seed="$vm-seed.iso"
    for volume in "$disk" "$seed"; do
        if vmctl vol-info "$volume" --pool "$pool" >/dev/null 2>&1; then
            die "Storage volume $volume already exists in $pool."
        fi
    done
    if [[ ! -f $temp/SHA256SUMS ]]; then
        get_image
    fi

    mkdir "$temp/$node"
    sed -e "s|@PUBLIC_KEY@|$key_type $key_data|" -e "s|@NODE@|$node|g" \
        "$root/infra/cloud-init.yaml" > "$temp/$node/user-data"
    printf 'instance-id: %s\nlocal-hostname: %s\n' "$vm" "$vm" > "$temp/$node/meta-data"
    genisoimage -quiet -volid cidata -joliet -rock -output "$temp/$seed" \
        "$temp/$node/user-data" "$temp/$node/meta-data"
    qemu-img convert -f qcow2 -O qcow2 "$image" "$temp/$disk"
    qemu-img resize -f qcow2 "$temp/$disk" 8G
    upload_volume "$disk" "$temp/$disk" 8G qcow2
    upload_volume "$seed" "$temp/$seed" "$(stat -c %s "$temp/$seed")" raw
    virt-install --connect "$uri" --name "$vm" --metadata "description=$description" \
        --import --osinfo generic --memory 1536 --vcpus 2 --noautoconsole \
        --network "network=$network,model=virtio,mac=52:54:00:51:00:0$node" \
        --disk "vol=$pool/$disk,bus=virtio,format=qcow2" \
        --disk "vol=$pool/$seed,device=cdrom,readonly=on"
    created_volumes=()
done
vm=

ssh_args=(
    -i "$key" -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=3
    -o "UserKnownHostsFile=$state/known_hosts" -o GlobalKnownHostsFile=/dev/null
    -o StrictHostKeyChecking=accept-new
)
for node in a b; do
    ip=192.168.151.11
    [[ $node == a ]] || ip=192.168.151.12
    deadline=$((SECONDS + 120))
    until ssh "${ssh_args[@]}" "lab@$ip" true >/dev/null 2>&1; do
        (( SECONDS < deadline )) || die "SSH did not come up for reservation-$node at $ip."
        sleep 2
    done
    timeout 480 ssh "${ssh_args[@]}" "lab@$ip" \
        "cloud-init status --wait && test \"\$(cat /etc/reservation-lab)\" = $node && java -version"
    printf '%s %s\n' "reservation-$node" "$ip"
done
